package com.souyu.forum.engine;

import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.forum.ForumConstants;
import com.souyu.common.forum.MessageType;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.producer.messageProducer;
import com.souyu.forum.agent.report;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Forum 消息处理器
 * 处理具体的业务逻辑：存储消息、触发总结等
 */
@Service
@ConditionalOnProperty(name = "forum.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ForumMessageHandler {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TaskStatusManager taskStatusManager;

    @Autowired
    private messageProducer producer;

    @Autowired
    private report reportService;

    private static final String COUNTER_KEY_PREFIX = "log:counter:";
    private static final String FINAL_CHECK_KEY_PREFIX = "forum:final:check:";
    private static final int SUMMARY_THRESHOLD = 5;

    /**
     * 处理消息入口
     *
     * @param taskId  任务ID
     * @param message 消息体
     */
    public void handle(String taskId, Map<Object, Object> message) {
        String typeStr = message.get("type") != null ? message.get("type").toString() : "CONTENT";

        MessageType type;
        try {
            type = MessageType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown message type: {}, treating as CONTENT", typeStr);
            type = MessageType.CONTENT;
        }

        switch (type) {
            case CONTENT -> handleContentMessage(taskId, message);
            case STATUS -> handleStatusMessage(taskId, message);
            case ERROR -> handleErrorMessage(taskId, message);
            case COMPLETE, FINAL_CHECK -> handleCompleteMessage(taskId, message);
            default -> log.warn("Unhandled message type: {}", type);
        }
    }

    /**
     * 处理内容消息
     */
    private void handleContentMessage(String taskId, Map<Object, Object> message) {
        String content = message.get("content") != null ? message.get("content").toString() : "";
        // 兼容两种字段名：sourceEngine (新) 和 engine (旧)
        String sourceEngine = message.get("sourceEngine") != null ?
                message.get("sourceEngine").toString() :
                (message.get("engine") != null ? message.get("engine").toString() : "unknown");

        log.debug("Handling content message for task {} from {}", taskId, sourceEngine);

        // 1. 追加到 MongoDB
        appendLogToMongo(taskId, sourceEngine, content);

        // 2. 递增计数器（使用 Redis 原子操作）
        String counterKey = COUNTER_KEY_PREFIX + taskId;
        Long count = redisTemplate.opsForValue().increment(counterKey);

        // 设置计数器过期时间（7天）
        if (count != null && count == 1) {
            redisTemplate.expire(counterKey, 7, TimeUnit.DAYS);
        }

        log.debug("Task {} log count: {}", taskId, count);

        // 3. 检查是否需要阶段性总结
        if (count != null && count % SUMMARY_THRESHOLD == 0) {
            log.info("Task {} reached threshold {}, performing intermediate summary", taskId, SUMMARY_THRESHOLD);
            performSummary(taskId, false);
        }
    }

    /**
     * 处理状态消息
     */
    private void handleStatusMessage(String taskId, Map<Object, Object> message) {
        String status = message.get("content") != null ? message.get("content").toString() : "";
        log.info("Received status message for task {}: {}", taskId, status);
        // 状态消息暂时只记录日志，可根据需要扩展
    }

    /**
     * 处理错误消息
     */
    private void handleErrorMessage(String taskId, Map<Object, Object> message) {
        String error = message.get("content") != null ? message.get("content").toString() : "Unknown error";
        log.error("Received error message for task {}: {}", taskId, error);

        // 更新任务状态为失败
        try {
            taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.FAILED);
        } catch (Exception e) {
            log.error("Failed to update forum status to FAILED for task {}", taskId, e);
        }
    }

    /**
     * 处理完成消息
     */
    private void handleCompleteMessage(String taskId, Map<Object, Object> message) {
        // 兼容两种字段名：sourceEngine (新) 和 engine (旧)
        String sourceEngine = message.get("sourceEngine") != null ?
                message.get("sourceEngine").toString() :
                (message.get("engine") != null ? message.get("engine").toString() : "unknown");

        log.info("Received complete message for task {} from {}", taskId, sourceEngine);

        // 检查是否已处理过最终总结（防止重复处理）
        String finalCheckKey = FINAL_CHECK_KEY_PREFIX + taskId;
        Boolean alreadyProcessed = redisTemplate.opsForValue().setIfAbsent(finalCheckKey, "1", 1, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(alreadyProcessed)) {
            log.warn("Final check already processed for task {}, skipping", taskId);
            // 确保发送 FORUM_COMPLETED 事件（以防上次没发送成功）
            markTaskCompleted(taskId);
            return;
        }

        // 检查最后一条是否已经是 HOST（避免重复总结）
        if (checkLastIsHost(taskId)) {
            log.info("Last log is already HOST for task {}, skipping summary", taskId);
            markTaskCompleted(taskId);
            return;
        }

        // 执行最终总结
        performSummary(taskId, true);
    }

    /**
     * 追加日志到 MongoDB
     */
    private void appendLogToMongo(String taskId, String engine, String content) {
        try {
            OneLog logEntry = new OneLog();
            logEntry.setEngine(engine);
            logEntry.setContent(content);
            logEntry.setTimeStamp(System.currentTimeMillis());

            Query query = new Query(Criteria.where("_id").is(taskId));
            Update update = new Update().push("content", logEntry);

            mongoTemplate.upsert(query, update, ForumLog.class, "forum_logs");

            log.debug("Appended log to MongoDB for task: {} from engine: {}", taskId, engine);

        } catch (Exception e) {
            log.error("Failed to append log to MongoDB for task: {}", taskId, e);
            throw new RuntimeException("Failed to append log", e);
        }
    }

    /**
     * 执行总结
     *
     * @param taskId  任务ID
     * @param isFinal 是否为最终总结
     */
    private void performSummary(String taskId, boolean isFinal) {
        try {
            log.info("Performing {} summary for task: {}", isFinal ? "final" : "intermediate", taskId);

            // 更新状态为 RUNNING
            taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.RUNNING);

            // 调用总结服务生成总结内容
            String summary = generateSummary(taskId);

            if (summary != null && !summary.isEmpty()) {
                // 保存总结到 MongoDB
                saveSummaryToMongo(taskId, summary);

                if (isFinal) {
                    markTaskCompleted(taskId);
                }
            } else {
                log.warn("Generated summary is empty for task: {}", taskId);
                if (isFinal) {
                    // 即使没有总结内容，也标记完成
                    markTaskCompleted(taskId);
                }
            }

        } catch (Exception e) {
            log.error("Failed to perform summary for task: {}", taskId, e);
            try {
                taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.FAILED);
            } catch (Exception ex) {
                log.error("Failed to update forum status to FAILED", ex);
            }
        }
    }

    /**
     * 生成总结（复用现有逻辑）
     * 调用 reportService.generateHostSpeech() 使用 LLM 生成
     */
    private String generateSummary(String taskId) {
        log.debug("Generating summary for task: {} using LLM", taskId);
        return reportService.generateHostSpeech(taskId);
    }

    /**
     * 保存总结到 MongoDB
     */
    private void saveSummaryToMongo(String taskId, String summary) {
        OneLog logEntry = new OneLog();
        logEntry.setEngine("HOST");
        logEntry.setContent(summary);
        logEntry.setTimeStamp(System.currentTimeMillis());

        Query query = new Query(Criteria.where("_id").is(taskId));
        Update update = new Update().push("content", logEntry);

        mongoTemplate.upsert(query, update, ForumLog.class, "forum_logs");

        log.info("Saved HOST summary to MongoDB for task: {}", taskId);
    }

    /**
     * 检查最后一条日志是否已经是 HOST
     */
    private boolean checkLastIsHost(String taskId) {
        try {
            Query query = new Query(Criteria.where("_id").is(taskId));
            ForumLog forumLog = mongoTemplate.findOne(query, ForumLog.class, "forum_logs");

            if (forumLog != null && forumLog.getContent() != null && !forumLog.getContent().isEmpty()) {
                OneLog lastLog = forumLog.getContent().get(forumLog.getContent().size() - 1);
                return "HOST".equals(lastLog.getEngine());
            }
        } catch (Exception e) {
            log.warn("Failed to check last log for task: {}", taskId, e);
        }
        return false;
    }

    /**
     * 标记任务完成
     */
    private void markTaskCompleted(String taskId) {
        boolean eventSent = false;
        try {
            // 先发送事件通知 Orchestrator（最重要）
            producer.triggerForumCompleted(taskId);
            eventSent = true;
            log.info("FORUM_COMPLETED event sent for task {}", taskId);
        } catch (Exception e) {
            log.error("Failed to send FORUM_COMPLETED event for task {}", taskId, e);
        }

        try {
            // 更新状态
            taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.COMPLETED);
            log.info("Task {} forum status updated to COMPLETED", taskId);
        } catch (Exception e) {
            log.error("Failed to update forum status for task {}", taskId, e);
        }

        try {
            // 清理计数器
            redisTemplate.delete(COUNTER_KEY_PREFIX + taskId);
        } catch (Exception e) {
            log.warn("Failed to clean up counter for task {}", taskId, e);
        }
    }

    // ==================== 内部 DTO 类 ====================

    @Data
    public static class ForumLog {
        private String taskId;
        private List<OneLog> content;
    }

    @Data
    public static class OneLog {
        private String engine;
        private String content;
        private long timeStamp;
    }
}
