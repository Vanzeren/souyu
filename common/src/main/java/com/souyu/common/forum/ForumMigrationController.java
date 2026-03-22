package com.souyu.common.forum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Forum 架构迁移管理端点
 * 用于查看迁移状态和手动控制迁移阶段
 */
@RestController
@RequestMapping("/api/admin/forum-migration")
@ConditionalOnProperty(name = "forum.failover.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class ForumMigrationController {

    @Autowired
    private DualWriteMessageService dualWriteService;

    @Autowired
    private ForumFailoverManager failoverManager;

    @Autowired
    private ForumSlotManager slotManager;

    /**
     * 获取迁移状态
     */
    @GetMapping("/status")
    public Map<String, Object> getMigrationStatus() {
        Map<String, Object> status = new HashMap<>();

        // 双写服务状态
        status.put("migration", dualWriteService.getMigrationStatus());

        // 集群健康状态
        status.put("clusterHealth", failoverManager.getClusterHealth());

        // 槽位分配统计
        status.put("slotAllocation", slotManager.getAllocationStats());

        return status;
    }

    /**
     * 获取集群健康详情
     */
    @GetMapping("/health")
    public ForumFailoverManager.HealthStatus getClusterHealth() {
        return failoverManager.getClusterHealth();
    }

    /**
     * 手动触发故障转移（运维操作）
     */
    @PostMapping("/failover")
    public Map<String, String> manualFailover(String nodeId) {
        Map<String, String> result = new HashMap<>();
        try {
            String message = failoverManager.manualFailover(nodeId);
            result.put("status", "success");
            result.put("message", message);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 初始化槽位分配（首次部署时使用）
     */
    @PostMapping("/init-slots")
    public Map<String, String> initializeSlots() {
        Map<String, String> result = new HashMap<>();
        try {
            // 获取所有健康节点
            var healthyNodes = failoverManager.getAllHealthyNodes();
            if (healthyNodes.isEmpty()) {
                result.put("status", "error");
                result.put("message", "No healthy forum nodes found");
                return result;
            }

            slotManager.initializeSlotAllocation(healthyNodes);
            result.put("status", "success");
            result.put("message", "Slot allocation initialized for " + healthyNodes.size() + " nodes");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 检查新架构健康状态
     */
    @GetMapping("/new-arch-health")
    public Map<String, Object> checkNewArchitectureHealth() {
        Map<String, Object> result = new HashMap<>();
        boolean healthy = dualWriteService.isNewArchitectureHealthy();
        result.put("healthy", healthy);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}
