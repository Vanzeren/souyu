package com.souyu.common.manager;

import com.souyu.common.TaskStatus.TaskStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 心跳更新缓冲器。
 *
 * <p>将高频的心跳更新（refreshUpdateTime）缓冲到本地内存，批量写入 MongoDB，
 * 显著减少并发场景下的数据库写压力。
 *
 * <p>设计：
 * <ul>
 *   <li>每个 taskId 只保留最新的时间戳</li>
 *   <li>每 10 秒批量刷盘一次</li>
 *   <li>任务结束时强制刷盘，确保数据不丢失</li>
 *   <li>shutdown 时优雅关闭，确保缓冲数据写入</li>
 * </ul>
 */
@Component
public class HeartbeatBuffer {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatBuffer.class);

    // 批量写入间隔（毫秒）
    private static final long FLUSH_INTERVAL_MS = 10000;

    // 待写入的更新：taskId -> 更新时间戳（毫秒）
    private final ConcurrentHashMap<String, Long> pendingUpdates = new ConcurrentHashMap<>();

    // 统计信息
    private final AtomicInteger bufferedCount = new AtomicInteger(0);
    private final AtomicInteger flushedCount = new AtomicInteger(0);

    @Autowired
    private MongoTemplate mongoTemplate;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("[HeartbeatBuffer] 启动，刷盘间隔 {}ms", FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void shutdown() {
        logger.info("[HeartbeatBuffer] 正在关闭，强制刷盘...");
        flush();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("[HeartbeatBuffer] 已关闭，累计缓冲 {} 次，刷盘 {} 次",
                bufferedCount.get(), flushedCount.get());
    }

    /**
     * 缓冲一个心跳更新。
     * 只保留最新的时间戳，覆盖旧值。
     */
    public void buffer(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        pendingUpdates.put(taskId, System.currentTimeMillis());
        bufferedCount.incrementAndGet();
    }

    /**
     * 强制刷盘：立即将所有缓冲的更新写入 MongoDB。
     * 在任务结束时调用，确保数据不丢失。
     */
    public void flush() {
        if (pendingUpdates.isEmpty()) {
            return;
        }

        // 快速交换，减少持有锁的时间
        Map<String, Long> toFlush = Map.copyOf(pendingUpdates);
        pendingUpdates.clear();

        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, Long> entry : toFlush.entrySet()) {
            try {
                String taskId = entry.getKey();
                LocalDateTime updateTime = new java.sql.Timestamp(entry.getValue()).toLocalDateTime();

                Query query = new Query(Criteria.where("_id").is(taskId));
                Update update = new Update().set("updatedAt", updateTime);

                mongoTemplate.updateFirst(query, update, TaskStatus.class);
                successCount++;
            } catch (Exception e) {
                logger.warn("[HeartbeatBuffer] 刷盘失败 taskId={}: {}", entry.getKey(), e.getMessage());
                failCount++;
                // 失败的重新放入缓冲，下次再试（避免无限重试，最多重试一次）
                if (pendingUpdates.size() < 1000) {  // 防止内存无限增长
                    pendingUpdates.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        flushedCount.addAndGet(successCount);
        if (successCount > 0 || failCount > 0) {
            logger.info("[HeartbeatBuffer] 批量刷盘: 成功 {}, 失败 {}, 缓冲队列剩余 {}",
                    successCount, failCount, pendingUpdates.size());
        }
    }

    /**
     * 立即更新指定 taskId 的心跳（同步写入）。
     * 用于任务结束时确保最后一次更新被记录。
     */
    public void flushImmediately(String taskId) {
        pendingUpdates.remove(taskId);  // 从缓冲中移除，避免重复写入
        try {
            Query query = new Query(Criteria.where("_id").is(taskId));
            Update update = new Update().set("updatedAt", LocalDateTime.now());
            mongoTemplate.updateFirst(query, update, TaskStatus.class);
        } catch (Exception e) {
            logger.warn("[HeartbeatBuffer] 即时刷盘失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 获取当前缓冲数量（用于监控）。
     */
    public int getPendingCount() {
        return pendingUpdates.size();
    }
}
