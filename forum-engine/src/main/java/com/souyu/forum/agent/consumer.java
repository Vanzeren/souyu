package com.souyu.forum.agent;

import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.producer.messageProducer;
import jakarta.el.ExpressionFactory;
import lombok.Data;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "forum.legacy-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class consumer implements InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(consumer.class);
    private final StringRedisTemplate redisTemplate;
    private final ExecutorService executorService;
    private volatile boolean running = true;

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private DefaultRedisScript<Long> logSummaryScript;
    @Autowired
    private report reportService;
    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private TaskStatusManager taskStatusManager; // 注入状态管理器
    
    @Autowired
    private messageProducer producer; // 注入消息生产者

    private final String LOCK_PREFIX="souyu:forum:lock:";
    private final String FINAL_CHECK_KEY_PREFIX = "forum:final:check:";
    private final String streamKey = "forum";
    private final String groupName = "forum-consumer";


    public consumer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.executorService = Executors.newFixedThreadPool(2); // 增加线程池大小以支持两个消费者任务

        // 初始化 Consumer Group
        try {
            // 1. 确保 Stream 存在
            Boolean hasKey = redisTemplate.hasKey(streamKey);
            if (Boolean.TRUE.equals(hasKey) == false) {
                logger.info("Stream {} does not exist, creating it.", streamKey);
                // 发送一条初始化消息来创建 Stream
                redisTemplate.opsForStream().add(MapRecord.create(streamKey, Map.of("init", "true")));
            }

            // 2. 创建 Consumer Group
            // ReadOffset.from("0-0") 表示从流的开始读取
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
            logger.info("Created consumer group: {}", groupName);

        } catch (Exception e) {
            // 3. 处理 Group 已存在的情况
            // 遍历异常链查找 "BUSYGROUP"
            boolean isBusyGroup = false;
            Throwable cause = e;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                    isBusyGroup = true;
                    break;
                }
                cause = cause.getCause();
            }

            if (isBusyGroup) {
                logger.info("Consumer Group {} already exists, skipping creation.", groupName);
            } else {
                logger.error("Failed to initialize consumer group", e);
                // 不抛出异常，以免阻止应用启动，但后续消费可能会出错
            }
        }
    }

    @Override
    public void afterPropertiesSet() {
        executorService.submit(this::consumeMessages);
    }

    private void consumeMessages() {
        logger.info("Starting to consume messages from stream: {} with group: {}", streamKey, groupName);
        String consumerName = "fc1";

        while (running) {
            try {
                // 使用消费者组模式读取
                // 修正泛型类型不匹配问题：StringRedisTemplate 默认返回 <String, Object, Object>
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                        Consumer.from(groupName, consumerName),
                        StreamReadOptions.empty().block(Duration.ofSeconds(2)),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                );

                if (messages != null && !messages.isEmpty()) {
                    for (MapRecord<String, Object, Object> message : messages) {
                        RLock lock = redissonClient.getLock(LOCK_PREFIX + message.getId());
                        boolean isLocked = false;
                        try {
                            // 尝试获取锁，等待 100ms，持有 30s
                            isLocked = lock.tryLock(100, 30000, TimeUnit.MILLISECONDS);
                            if (isLocked) {
                                handleMessage(message);
                                redisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                            } else {
                                logger.warn("Could not acquire lock for message {}, skipping.", message.getId());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.error("Interrupted while waiting for lock", e);
                        } finally {
                            if (isLocked && lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error consuming messages from stream {}", streamKey, e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleMessage(MapRecord<String, Object, Object> message) {

        try {
            Map<Object, Object> body = message.getValue();
            // 过滤掉初始化消息
            if (body.containsKey("init")) return;

            // 安全地转换为 String
            String taskId = body.get("taskId") != null ? body.get("taskId").toString() : null;
            String content = body.get("content") != null ? body.get("content").toString() : null;
            // 兼容两种字段名：sourceEngine (新) 和 engine (旧)
            String engine = body.get("sourceEngine") != null ?
                    body.get("sourceEngine").toString() :
                    (body.get("engine") != null ? body.get("engine").toString() : "unknown");
            String type = body.get("type") != null ? body.get("type").toString() : null;

            if (taskId == null) {
                logger.warn("Received message without taskId, skipping: {}", message.getId());
                return;
            }
            
            // 处理 Master 发来的 FINAL_CHECK 指令
            if ("FINAL_CHECK".equals(type) && "MASTER".equals(engine)) {
                logger.info("Received FINAL_CHECK from Master for task: {}", taskId);
                performFinalCheck(taskId);
                return;
            }

            logger.info("Received message: ID={}, TaskId={}, ContentLength={}",
                    message.getId(), taskId, (content != null ? content.length() : 0));

            // 加任务锁，防止与 handlePreFinishedMessage 冲突
            RLock taskLock = redissonClient.getLock("souyu:forum:tasklock:" + taskId);
            // 尝试获取任务锁，避免死锁
            if (taskLock.tryLock(5, 60, TimeUnit.SECONDS)) {
                try {
                    OneLog log = new OneLog();
                    log.setEngine(engine);
                    log.setContent(content);
                    log.setTimeStamp(System.currentTimeMillis());

                    // 使用 MongoDB 的 upsert + $push
                    Query query = new Query(Criteria.where("_id").is(taskId));
                    Update update = new Update().push("content", log);

                    mongoTemplate.upsert(query, update, ForumLog.class, "forum_logs");

                    logger.info("Appended log to MongoDB for task: {}", taskId);

                    // 2. 执行 Lua 脚本
                    // 只传递计数器 Key，不传递内容
                    Long result = redisTemplate.execute(
                            logSummaryScript,
                            Arrays.asList("log:counter:" + taskId), // KEYS
                            "5", "86400" // ARGV: 阈值, 过期时间
                    );

                    // 3. 如果返回 1，说明达到阈值
                    if (result != null && result == 1) {
                        performSummary(taskId, false); // 阶段性总结，不标记为 COMPLETED
                    }
                } finally {
                    taskLock.unlock();
                }
            } else {
                logger.warn("Could not acquire task lock for task {}, skipping message processing.", taskId);
            }

        } catch (Exception e) {
            logger.error("Failed to process message: {}", message.getId(), e);
        }
    }
    
    /**
     * 执行最终检查 (响应 Master 的 FINAL_CHECK)
     */
    private void performFinalCheck(String taskId) {
        // 检查是否已处理过最终总结（防止重复处理）
        String finalCheckKey = FINAL_CHECK_KEY_PREFIX + taskId;
        Boolean alreadyProcessed = redisTemplate.opsForValue().setIfAbsent(finalCheckKey, "1", 1, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(alreadyProcessed)) {
            logger.warn("Final check already processed for task {}, skipping", taskId);
            // 确保发送 FORUM_COMPLETED 事件（以防上次没发送成功）
            sendForumCompletedEvent(taskId);
            return;
        }

        RLock taskLock = redissonClient.getLock("souyu:forum:tasklock:" + taskId);
        try {
            if (taskLock.tryLock(5, 60, TimeUnit.SECONDS)) {
                try {
                    if (!checkLastIsHost(taskId)) {
                        logger.info("Final check: Last log is NOT HOST, performing summary for task: {}", taskId);
                        performSummary(taskId, true); // 最终总结，标记为 COMPLETED
                    } else {
                        logger.info("Final check: Last log is already HOST, marking as COMPLETED for task: {}", taskId);
                        // 即使已经是 HOST，也要确保状态是 COMPLETED 并发送事件，以防万一
                        sendForumCompletedEvent(taskId);
                    }
                } finally {
                    taskLock.unlock();
                }
            } else {
                 logger.warn("Could not acquire task lock for final check on task {}", taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for task lock", e);
        }
    }
    
    /**
     * 执行总结逻辑
     * @param isFinal 是否为最终总结
     */
    private void performSummary(String taskId, boolean isFinal) {
        // 在生成总结前，先检查最后一条是否已经是 HOST，避免重复总结
        if (!checkLastIsHost(taskId)) {
            // 更新 Forum 状态为 RUNNING
            taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.RUNNING);
            
            String summary = reportService.generateHostSpeech(taskId);
            if (summary != null) {
                saveSummaryToMongo(taskId, summary);
                
                if (isFinal) {
                    // 只有最终总结才更新为 COMPLETED 并发送事件
                    sendForumCompletedEvent(taskId);
                } else {
                    // 阶段性总结，状态可以保持 RUNNING
                    logger.info("Intermediate summary generated for task: {}", taskId);
                }
            } else {
                 taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.FAILED);
            }
        } else {
            logger.info("Last log is already HOST, skipping summary generation for task: {}", taskId);
            if (isFinal) {
                 sendForumCompletedEvent(taskId);
            }
        }
    }

    /**
     * 发送 FORUM_COMPLETED 事件并更新状态
     * 确保事件发送优先，即使状态更新失败也要通知 Orchestrator
     */
    private void sendForumCompletedEvent(String taskId) {
        try {
            // 先发送事件通知 Orchestrator（最重要）
            producer.triggerForumCompleted(taskId);
            logger.info("FORUM_COMPLETED event sent for task {}", taskId);
        } catch (Exception e) {
            logger.error("Failed to send FORUM_COMPLETED event for task {}", taskId, e);
        }

        try {
            // 更新状态
            taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.COMPLETED);
            logger.info("Task {} forum status updated to COMPLETED", taskId);
        } catch (Exception e) {
            logger.error("Failed to update forum status for task {}", taskId, e);
        }
    }

    private boolean checkLastIsHost(String taskId) {
        Query query = new Query(Criteria.where("_id").is(taskId));
        ForumLog forumLog = mongoTemplate.findOne(query, ForumLog.class, "forum_logs");
        
        if (forumLog != null && forumLog.getContent() != null && !forumLog.getContent().isEmpty()) {
            OneLog lastLog = forumLog.getContent().get(forumLog.getContent().size() - 1);
            return "HOST".equals(lastLog.getEngine());
        }
        return false;
    }

    private void saveSummaryToMongo(String taskId, String summary) {
        OneLog log = new OneLog();
        log.setEngine("HOST");
        log.setContent(summary);
        log.setTimeStamp(System.currentTimeMillis());

        Query query = new Query(Criteria.where("_id").is(taskId));
        Update update = new Update().push("content", log);
        mongoTemplate.upsert(query, update, ForumLog.class, "forum_logs");
        logger.info("Saved HOST summary to MongoDB for task: {}", taskId);
    }

    @Override
    public void destroy() {
        this.running = false;
        this.executorService.shutdown();
    }

    @Data
    public static class ForumLog {
        @org.springframework.data.annotation.Id
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
