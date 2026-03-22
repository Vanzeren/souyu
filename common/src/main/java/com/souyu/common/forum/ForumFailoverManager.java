package com.souyu.common.forum;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Forum 故障转移管理器
 * 负责节点健康检测和故障转移
 */
@Component
@ConditionalOnProperty(name = "forum.failover.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class ForumFailoverManager {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ForumSlotManager slotManager;

    private DefaultRedisScript<Long> failoverScript;

    private ScheduledExecutorService healthCheckExecutor;

    @PostConstruct
    public void init() {
        failoverScript = new DefaultRedisScript<>(
                ForumConstants.LUA_FAILOVER, Long.class);

        // 启动定时健康检测线程
        startHealthCheckScheduler();

        log.info("ForumFailoverManager initialized with health check scheduler");
    }

    /**
     * 启动健康检测定时任务
     */
    private void startHealthCheckScheduler() {
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "forum-health-check");
            t.setDaemon(true);
            return t;
        });

        // 每 10 秒执行一次健康检测
        healthCheckExecutor.scheduleAtFixedRate(
                this::healthCheck,
                10,  // 初始延迟 10 秒
                10,  // 周期 10 秒
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down ForumFailoverManager...");
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 定时健康检测
     */
    private void healthCheck() {
        try {
            log.debug("Starting health check...");

            Set<String> deadNodes = detectDeadNodes();

            if (deadNodes.isEmpty()) {
                log.debug("All nodes are healthy");
                return;
            }

            log.warn("Detected {} dead nodes: {}", deadNodes.size(), deadNodes);

            for (String deadNodeId : deadNodes) {
                try {
                    performFailover(deadNodeId);
                } catch (Exception e) {
                    log.error("Failed to perform failover for node: {}", deadNodeId, e);
                }
            }

        } catch (Exception e) {
            log.error("Health check failed", e);
        }
    }

    /**
     * 检测死亡节点
     *
     * @return 死亡节点ID集合
     */
    public Set<String> detectDeadNodes() {
        Set<String> deadNodes = new HashSet<>();

        // 获取所有节点 key
        Set<String> nodeKeys = redisTemplate.keys(ForumConstants.KEY_NODE_PREFIX + "*");

        if (nodeKeys == null || nodeKeys.isEmpty()) {
            log.warn("No forum nodes found");
            return deadNodes;
        }

        for (String key : nodeKeys) {
            // 提取 nodeId
            String nodeId = key.substring(ForumConstants.KEY_NODE_PREFIX.length());

            // 检查节点是否存在（TTL 是否过期）
            Boolean exists = redisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(exists)) {
                log.warn("Node {} key does not exist or expired", nodeId);
                deadNodes.add(nodeId);
                continue;
            }

            // 检查心跳时间
            String heartbeatStr = (String) redisTemplate.opsForHash()
                    .get(key, "lastHeartbeat");

            if (heartbeatStr == null) {
                log.warn("Node {} has no heartbeat", nodeId);
                deadNodes.add(nodeId);
                continue;
            }

            try {
                long lastHeartbeat = Long.parseLong(heartbeatStr);
                long now = System.currentTimeMillis();
                long elapsed = now - lastHeartbeat;

                // 超过 3 个心跳周期（15秒）认为死亡
                if (elapsed > ForumConstants.NODE_TTL_SECONDS * 1000L) {
                    log.warn("Node {} heartbeat timeout, last: {}ms ago", nodeId, elapsed);
                    deadNodes.add(nodeId);
                }

            } catch (NumberFormatException e) {
                log.warn("Invalid heartbeat format for node: {}", nodeId);
                deadNodes.add(nodeId);
            }
        }

        return deadNodes;
    }

    /**
     * 执行故障转移
     *
     * @param deadNodeId 死亡节点ID
     */
    public void performFailover(String deadNodeId) {
        log.warn("Performing failover for dead node: {}", deadNodeId);

        // 1. 获取健康节点列表
        List<String> healthyNodes = getHealthyNodes(deadNodeId);

        if (healthyNodes.isEmpty()) {
            log.error("No healthy nodes available for failover");
            // 仍然需要标记任务为 FAILED，但无法迁移槽位
        }

        // 2. 迁移槽位（如果有健康节点）
        if (!healthyNodes.isEmpty()) {
            try {
                slotManager.emergencyMigrate(deadNodeId, healthyNodes);
                log.info("Migrated slots from {} to healthy nodes", deadNodeId);
            } catch (Exception e) {
                log.error("Failed to migrate slots from {}", deadNodeId, e);
            }
        }

        // 3. 标记死亡节点的所有任务为 FAILED（使用 Lua 脚本）
        long failedCount = markTasksAsFailed(deadNodeId);
        log.info("Marked {} tasks as FAILED for node {}", failedCount, deadNodeId);

        // 4. 清理死亡节点数据
        cleanupDeadNode(deadNodeId);

        log.info("Failover completed for node: {}", deadNodeId);
    }

    /**
     * 使用 Lua 脚本原子标记任务为 FAILED
     *
     * @param deadNodeId 死亡节点ID
     * @return 被标记的任务数量
     */
    private long markTasksAsFailed(String deadNodeId) {
        try {
            Long result = redisTemplate.execute(
                    failoverScript,
                    Collections.emptyList(),  // KEYS 在脚本内部构建
                    deadNodeId
            );

            return result != null ? result : 0;

        } catch (Exception e) {
            log.error("Failed to execute failover script for node: {}", deadNodeId, e);

            // Lua 脚本失败，使用备用方案（逐个处理）
            return markTasksAsFailedFallback(deadNodeId);
        }
    }

    /**
     * 备用方案：逐个标记任务为 FAILED
     *
     * @param deadNodeId 死亡节点ID
     * @return 被标记的任务数量
     */
    private long markTasksAsFailedFallback(String deadNodeId) {
        String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, deadNodeId);
        Set<String> taskIds = redisTemplate.opsForSet().members(tasksKey);

        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String taskId : taskIds) {
            try {
                String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);
                redisTemplate.opsForHash().put(bindingKey, "status", "FAILED");
                count++;
            } catch (Exception e) {
                log.error("Failed to mark task {} as FAILED", taskId, e);
            }
        }

        return count;
    }

    /**
     * 清理死亡节点数据
     *
     * @param deadNodeId 死亡节点ID
     */
    private void cleanupDeadNode(String deadNodeId) {
        try {
            // 删除节点信息
            String nodeKey = ForumConstants.KEY_NODE_PREFIX + deadNodeId;
            redisTemplate.delete(nodeKey);

            // 删除节点任务集合
            String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, deadNodeId);
            redisTemplate.delete(tasksKey);

            log.info("Cleaned up data for dead node: {}", deadNodeId);

        } catch (Exception e) {
            log.error("Failed to cleanup data for dead node: {}", deadNodeId, e);
        }
    }

    /**
     * 获取健康节点列表
     *
     * @param excludeNodeId 要排除的节点ID（死亡节点）
     * @return 健康节点ID列表
     */
    public List<String> getHealthyNodes(String excludeNodeId) {
        List<String> healthyNodes = new ArrayList<>();

        Set<String> nodeKeys = redisTemplate.keys(ForumConstants.KEY_NODE_PREFIX + "*");

        if (nodeKeys == null) {
            return healthyNodes;
        }

        for (String key : nodeKeys) {
            String nodeId = key.substring(ForumConstants.KEY_NODE_PREFIX.length());

            // 排除指定节点
            if (nodeId.equals(excludeNodeId)) {
                continue;
            }

            // 检查节点是否存活
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                healthyNodes.add(nodeId);
            }
        }

        return healthyNodes;
    }

    /**
     * 获取所有健康节点
     *
     * @return 健康节点ID列表
     */
    public List<String> getAllHealthyNodes() {
        return getHealthyNodes(null);
    }

    /**
     * 手动触发故障转移（用于运维）
     *
     * @param nodeId 要转移的节点ID
     * @return 转移结果
     */
    public String manualFailover(String nodeId) {
        log.info("Manual failover triggered for node: {}", nodeId);

        // 检查节点是否真的死亡
        String nodeKey = ForumConstants.KEY_NODE_PREFIX + nodeId;
        Boolean exists = redisTemplate.hasKey(nodeKey);

        if (Boolean.TRUE.equals(exists)) {
            // 节点还活着，询问是否强制转移
            log.warn("Node {} is still alive, use force=true to force failover", nodeId);
            return "Node is still alive";
        }

        performFailover(nodeId);
        return "Failover completed for " + nodeId;
    }

    /**
     * 获取集群健康状态
     *
     * @return 健康状态报告
     */
    public HealthStatus getClusterHealth() {
        HealthStatus status = new HealthStatus();

        Set<String> nodeKeys = redisTemplate.keys(ForumConstants.KEY_NODE_PREFIX + "*");

        if (nodeKeys == null || nodeKeys.isEmpty()) {
            status.setStatus("NO_NODES");
            return status;
        }

        int totalNodes = 0;
        int healthyNodes = 0;
        int totalLoad = 0;
        int totalCapacity = 0;

        for (String key : nodeKeys) {
            totalNodes++;

            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                healthyNodes++;

                String loadStr = (String) redisTemplate.opsForHash().get(key, "load");
                String capacityStr = (String) redisTemplate.opsForHash().get(key, "capacity");

                if (loadStr != null) {
                    try {
                        totalLoad += Integer.parseInt(loadStr);
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (capacityStr != null) {
                    try {
                        totalCapacity += Integer.parseInt(capacityStr);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        status.setTotalNodes(totalNodes);
        status.setHealthyNodes(healthyNodes);
        status.setTotalLoad(totalLoad);
        status.setTotalCapacity(totalCapacity);
        status.setStatus(healthyNodes == totalNodes ? "HEALTHY" : "DEGRADED");

        return status;
    }

    /**
     * 健康状态数据类
     */
    public static class HealthStatus {
        private String status;
        private int totalNodes;
        private int healthyNodes;
        private int totalLoad;
        private int totalCapacity;

        public double getLoadRatio() {
            if (totalCapacity <= 0) {
                return 0;
            }
            return (double) totalLoad / totalCapacity;
        }

        // Getters and Setters
        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getTotalNodes() {
            return totalNodes;
        }

        public void setTotalNodes(int totalNodes) {
            this.totalNodes = totalNodes;
        }

        public int getHealthyNodes() {
            return healthyNodes;
        }

        public void setHealthyNodes(int healthyNodes) {
            this.healthyNodes = healthyNodes;
        }

        public int getTotalLoad() {
            return totalLoad;
        }

        public void setTotalLoad(int totalLoad) {
            this.totalLoad = totalLoad;
        }

        public int getTotalCapacity() {
            return totalCapacity;
        }

        public void setTotalCapacity(int totalCapacity) {
            this.totalCapacity = totalCapacity;
        }

        @Override
        public String toString() {
            return String.format("HealthStatus{status='%s', nodes=%d/%d, load=%d/%d (%.1f%%)}",
                    status, healthyNodes, totalNodes, totalLoad, totalCapacity, getLoadRatio() * 100);
        }
    }
}
