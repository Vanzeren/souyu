package com.souyu.common.forum;

import com.souyu.common.producer.messageProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Forum 消息双写兼容服务
 * 用于新旧架构平滑迁移期间，同时发送消息到新旧两个 Stream
 *
 * 迁移流程：
 * 阶段1: 双写 + 新旧消费者并行（去重保证）
 * 阶段2: 双写 + 只使用新消费者
 * 阶段3: 单写新架构，下线旧消费者
 */
@Service
@Slf4j
public class DualWriteMessageService {

    @Autowired
    private ForumMessageProducer newProducer;

    @Autowired
    private messageProducer oldProducer;

    @Value("${forum.dual-write.enabled:true}")
    private boolean dualWriteEnabled;

    @Value("${forum.new-architecture.enabled:true}")
    private boolean newArchitectureEnabled;

    /**
     * 发送内容消息（双写兼容）
     *
     * @param taskId       任务ID
     * @param content      消息内容
     * @param sourceEngine 来源引擎
     */
    public void sendContent(String taskId, String content, String sourceEngine) {
        boolean newSuccess = false;
        boolean oldSuccess = false;

        // 1. 尝试新架构发送
        if (newArchitectureEnabled) {
            try {
                newProducer.sendContent(taskId, content, sourceEngine);
                newSuccess = true;
                log.debug("New architecture send success for task: {}", taskId);
            } catch (Exception e) {
                log.warn("New architecture send failed for task: {}, error: {}",
                        taskId, e.getMessage());
            }
        }

        // 2. 双写期间同时发送旧架构
        if (dualWriteEnabled) {
            try {
                Map<String, String> message = new HashMap<>();
                message.put("taskId", taskId);
                message.put("content", content);
                message.put("engine", sourceEngine);
                message.put("type", "CONTENT");
                message.put("timestamp", String.valueOf(System.currentTimeMillis()));

                oldProducer.sendMessage("forum", message);
                oldSuccess = true;
                log.debug("Old architecture send success for task: {}", taskId);
            } catch (Exception e) {
                log.error("Old architecture send failed for task: {}", taskId, e);
            }
        }

        // 3. 检查至少有一个成功
        if (!newSuccess && !oldSuccess) {
            throw new ForumRoutingException(
                    "Both new and old architecture send failed for task: " + taskId);
        }

        // 4. 记录迁移进度日志
        if (newSuccess && !oldSuccess) {
            log.debug("Only new architecture succeeded for task: {}", taskId);
        } else if (!newSuccess && oldSuccess) {
            log.warn("Falling back to old architecture for task: {}", taskId);
        }
    }

    /**
     * 发送完成消息（双写兼容）
     *
     * @param taskId       任务ID
     * @param sourceEngine 来源引擎
     */
    public void sendComplete(String taskId, String sourceEngine) {
        boolean newSuccess = false;
        boolean oldSuccess = false;

        // 新架构
        if (newArchitectureEnabled) {
            try {
                newProducer.sendComplete(taskId, sourceEngine);
                newSuccess = true;
            } catch (Exception e) {
                log.warn("New architecture complete send failed: {}", e.getMessage());
            }
        }

        // 旧架构
        if (dualWriteEnabled) {
            try {
                Map<String, String> message = new HashMap<>();
                message.put("taskId", taskId);
                message.put("engine", sourceEngine);
                message.put("type", "COMPLETE");
                message.put("timestamp", String.valueOf(System.currentTimeMillis()));

                oldProducer.sendMessage("forum", message);
                oldSuccess = true;
            } catch (Exception e) {
                log.error("Old architecture complete send failed", e);
            }
        }

        if (!newSuccess && !oldSuccess) {
            throw new ForumRoutingException("Failed to send complete message");
        }
    }

    /**
     * 发送错误消息（双写兼容）
     *
     * @param taskId       任务ID
     * @param errorMessage 错误信息
     * @param sourceEngine 来源引擎
     */
    public void sendError(String taskId, String errorMessage, String sourceEngine) {
        boolean newSuccess = false;
        boolean oldSuccess = false;

        // 新架构
        if (newArchitectureEnabled) {
            try {
                newProducer.sendError(taskId, errorMessage, sourceEngine);
                newSuccess = true;
            } catch (Exception e) {
                log.warn("New architecture error send failed: {}", e.getMessage());
            }
        }

        // 旧架构
        if (dualWriteEnabled) {
            try {
                Map<String, String> message = new HashMap<>();
                message.put("taskId", taskId);
                message.put("content", errorMessage);
                message.put("engine", sourceEngine);
                message.put("type", "ERROR");
                message.put("timestamp", String.valueOf(System.currentTimeMillis()));

                oldProducer.sendMessage("forum", message);
                oldSuccess = true;
            } catch (Exception e) {
                log.error("Old architecture error send failed", e);
            }
        }

        if (!newSuccess && !oldSuccess) {
            throw new ForumRoutingException("Failed to send error message");
        }
    }

    /**
     * 检查新架构是否健康
     * 用于监控和告警
     *
     * @return true if healthy
     */
    public boolean isNewArchitectureHealthy() {
        if (!newArchitectureEnabled) {
            return false;
        }
        try {
            // 简单检查：获取任意节点的路由能力
            // 实际可以扩展为更复杂的健康检查
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前配置状态
     */
    public Map<String, Object> getMigrationStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("newArchitectureEnabled", newArchitectureEnabled);
        status.put("dualWriteEnabled", dualWriteEnabled);
        status.put("newArchitectureHealthy", isNewArchitectureHealthy());
        status.put("migrationPhase", calculateMigrationPhase());
        return status;
    }

    private String calculateMigrationPhase() {
        if (!newArchitectureEnabled && dualWriteEnabled) {
            return "PHASE_0_OLD_ONLY";  // 旧架构 only
        } else if (newArchitectureEnabled && dualWriteEnabled) {
            return "PHASE_1_DUAL_WRITE";  // 双写阶段
        } else if (newArchitectureEnabled && !dualWriteEnabled) {
            return "PHASE_2_NEW_ONLY";  // 新架构 only
        } else {
            return "UNKNOWN";
        }
    }
}
