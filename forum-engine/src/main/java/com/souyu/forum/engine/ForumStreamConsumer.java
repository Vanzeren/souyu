package com.souyu.forum.engine;

import com.souyu.common.forum.ForumConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Forum Stream 消费者
 * 消费当前节点独立的 Stream，处理任务消息
 */
@Component
@ConditionalOnProperty(name = "forum.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ForumStreamConsumer {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ForumNodeLifecycle nodeLifecycle;

    @Autowired
    private ForumMessageHandler messageHandler;

    private String streamKey;
    private String groupName = ForumConstants.CONSUMER_GROUP;
    private volatile boolean running = true;
    private ExecutorService consumerExecutor;

    // 本地任务锁：每个 taskId 对应一个锁对象
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 构建当前节点的 Stream Key
        this.streamKey = ForumConstants.KEY_STREAM_PREFIX + nodeLifecycle.getNodeId();

        // 创建消费者组
        createConsumerGroup();

        // 启动消费线程池
        this.consumerExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "forum-consumer-" + nodeLifecycle.getNodeId());
            t.setDaemon(true);
            return t;
        });

        // 启动消费线程
        consumerExecutor.submit(this::consumeMessages);

        log.info("Forum stream consumer started for {}", streamKey);
    }

    /**
     * 创建消费者组
     */
    private void createConsumerGroup() {
        try {
            // 确保 Stream 存在（添加一条初始化消息）
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
                log.info("Stream {} does not exist, creating it", streamKey);
                redisTemplate.opsForStream().add(
                        StreamRecords.newRecord()
                                .in(streamKey)
                                .ofObject(Map.of("init", "true", "nodeId", nodeLifecycle.getNodeId()))
                );
            }

            // 创建消费者组
            redisTemplate.opsForStream().createGroup(
                    streamKey,
                    ReadOffset.from("0-0"),
                    groupName
            );

            log.info("Created consumer group: {} for stream: {}", groupName, streamKey);

        } catch (Exception e) {
            // 检查是否是因为组已存在
            Throwable cause = e;
            boolean isBusyGroup = false;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                    isBusyGroup = true;
                    break;
                }
                cause = cause.getCause();
            }

            if (isBusyGroup) {
                log.info("Consumer group {} already exists for stream {}", groupName, streamKey);
            } else {
                log.error("Failed to create consumer group for stream: {}", streamKey, e);
            }
        }
    }

    /**
     * 消费消息主循环
     */
    private void consumeMessages() {
        String consumerName = nodeLifecycle.getNodeId();

        while (running) {
            try {
                // 使用消费者组模式阻塞读取
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> messages = redisTemplate
                        .opsForStream()
                        .read(
                                Consumer.from(groupName, consumerName),
                                StreamReadOptions.empty()
                                        .block(Duration.ofMillis(ForumConstants.BLOCK_TIMEOUT_MS))
                                        .count(10),  // 每次最多读取 10 条
                                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                        );

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                log.debug("Received {} messages from {}", messages.size(), streamKey);

                for (MapRecord<String, Object, Object> message : messages) {
                    processMessageWithLock(message);
                }

            } catch (Exception e) {
                if (running) {
                    log.error("Error consuming messages from stream: {}", streamKey, e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.info("Message consumer stopped for {}", streamKey);
    }

    /**
     * 使用任务锁处理消息
     * 关键：防止同一任务的多个消息并发处理
     * 使用本地锁：因为任务与 forum 节点绑定，不需要分布式锁
     */
    private void processMessageWithLock(MapRecord<String, Object, Object> message) {
        RecordId messageId = message.getId();
        Map<Object, Object> body = message.getValue();

        // 过滤初始化消息
        if (body.containsKey("init")) {
            acknowledge(messageId);
            return;
        }

        // 获取 taskId
        String taskId = body.get("taskId") != null ? body.get("taskId").toString() : null;
        if (taskId == null) {
            log.warn("Message without taskId, skipping: {}", messageId);
            acknowledge(messageId);  // 无 taskId 的消息无法处理，直接 ACK
            return;
        }

        // 【关键】获取任务级本地锁
        Object taskLock = taskLocks.computeIfAbsent(taskId, k -> new Object());

        synchronized (taskLock) {
            try {
                // 处理消息
                messageHandler.handle(taskId, body);

                // 确认消息
                acknowledge(messageId);

                log.debug("Processed and acknowledged message {} for task {}",
                        messageId, taskId);

            } catch (Exception e) {
                log.error("Failed to process message {} for task {}", messageId, taskId, e);
                // 不 ACK，让消息重试
                return;
            }

            // 任务完成后清理锁对象（如果集合为空）
            // 注意：这里不立即移除，因为可能有其他消息在队列中等待处理同一任务
            // 锁对象会在节点销毁时统一清理，或可以添加惰性清理机制
        }
    }

    /**
     * 确认消息
     */
    private void acknowledge(RecordId messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, groupName, messageId);
        } catch (Exception e) {
            log.error("Failed to acknowledge message: {}", messageId, e);
        }
    }

    /**
     * 优雅停止
     */
    @PreDestroy
    public void destroy() {
        log.info("Stopping forum stream consumer...");
        running = false;

        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    consumerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                consumerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("Forum stream consumer stopped");
    }
}
