package com.souyu.common.manager;

import com.souyu.common.TaskStatus.TaskStatus;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class TaskStatusManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskStatusManager.class);
    private static final String LOCK_PREFIX = "lock:task:status:";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private HeartbeatBuffer heartbeatBuffer;

    /**
     * 初始化任务状态
     */
    public void initTask(String taskId) {
        TaskStatus taskStatus = new TaskStatus(taskId);
        mongoTemplate.save(taskStatus);
        logger.info("Task status initialized for taskId: {}", taskId);
    }

    /**
     * 获取任务状态
     */
    public TaskStatus getTaskStatus(String taskId) {
        return mongoTemplate.findById(taskId, TaskStatus.class);
    }

    /**
     * 查找超时任务
     * @param status 任务状态
     * @param timeoutThreshold 超时时间（分钟）
     * @return 超时任务列表
     */
    public List<TaskStatus> findStuckTasks(TaskStatus.Status status, int timeoutThreshold) {
        LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(timeoutThreshold);
        Query query = new Query(Criteria.where("status").is(status)
                .and("updatedAt").lt(thresholdTime));
        return mongoTemplate.find(query, TaskStatus.class);
    }
    
    /**
     * 增加重试计数并更新时间
     */
    public void incrementRetryCount(String taskId) {
        executeWithLock(taskId, (taskStatus) -> {
            taskStatus.incrementRetryCount();
            taskStatus.setUpdatedAt(LocalDateTime.now());
            mongoTemplate.save(taskStatus);
        });
    }
    
    /**
     * 刷新任务更新时间 (心跳)
     * 使用 HeartbeatBuffer 缓冲批量写入，减少 MongoDB 写压力。
     */
    public void refreshUpdateTime(String taskId) {
        // 改为缓冲写入，每 10 秒批量刷盘
        heartbeatBuffer.buffer(taskId);
        logger.debug("Heartbeat buffered for task: {}", taskId);
    }

    /**
     * 立即刷新任务更新时间（同步写入）
     * 在任务结束时调用，确保数据立即持久化
     */
    public void refreshUpdateTimeImmediately(String taskId) {
        heartbeatBuffer.flushImmediately(taskId);
        logger.info("Heartbeat immediately flushed for task: {}", taskId);
    }

    /**
     * 更新 Worker (Query/Media Engine) 的状态
     * 线程安全
     */
    public void updateWorkerStatus(String taskId, String workerName, TaskStatus.WorkerStatus status) {
        executeWithLock(taskId, (taskStatus) -> {
            taskStatus.getWorkerStatus().put(workerName, status);
            taskStatus.setUpdatedAt(LocalDateTime.now());
            
            // 移除自动流转逻辑，交由 Master 处理
            // if (status == TaskStatus.WorkerStatus.COMPLETED && areAllWorkersCompleted(taskStatus)) { ... }
            
            mongoTemplate.save(taskStatus);
            logger.info("Updated worker {} status to {} for task {}", workerName, status, taskId);
        });
    }

    /**
     * 更新 Forum Host 的状态
     * 线程安全
     */
    public void updateForumStatus(String taskId, TaskStatus.WorkerStatus status) {
        executeWithLock(taskId, (taskStatus) -> {
            taskStatus.setForumStatus(status);
            taskStatus.setUpdatedAt(LocalDateTime.now());

            // 移除自动流转逻辑，交由 Master 处理
            // if (status == TaskStatus.WorkerStatus.COMPLETED && taskStatus.getStatus() == TaskStatus.Status.SUMMARIZING) { ... }

            mongoTemplate.save(taskStatus);
            logger.info("Updated forum status to {} for task {}", status, taskId);
        });
    }

    /**
     * 更新主任务状态
     * 线程安全
     */
    public void updateMainStatus(String taskId, TaskStatus.Status status) {
        executeWithLock(taskId, (taskStatus) -> {
            taskStatus.setStatus(status);
            taskStatus.setUpdatedAt(LocalDateTime.now());
            mongoTemplate.save(taskStatus);
            logger.info("Updated main status to {} for task {}", status, taskId);
        });
    }

    /**
     * 标记任务完成
     */
    public void markTaskCompleted(String taskId, String reportId) {
        executeWithLock(taskId, (taskStatus) -> {
            taskStatus.setStatus(TaskStatus.Status.COMPLETED);
            taskStatus.setReportId(reportId);
            taskStatus.setUpdatedAt(LocalDateTime.now());
            mongoTemplate.save(taskStatus);
            logger.info("Task {} completed with reportId: {}", taskId, reportId);
        });
    }

    /**
     * 标记任务失败
     */
    public void markTaskFailed(String taskId, String errorMessage) {
        executeWithLock(taskId, (taskStatus) -> {
            taskStatus.setStatus(TaskStatus.Status.FAILED);
            taskStatus.setErrorMessage(errorMessage);
            taskStatus.setUpdatedAt(LocalDateTime.now());
            mongoTemplate.save(taskStatus);
            logger.error("Task {} failed: {}", taskId, errorMessage);
        });
    }

    /**
     * 检查是否所有注册的 Worker 都已完成
     * 这里假设我们知道有哪些 Worker，或者根据已有的 key 判断
     * 如果需要严格校验，可以在 initTask 时预设 worker keys
     */
    public boolean areAllWorkersCompleted(TaskStatus taskStatus) {
        Map<String, TaskStatus.WorkerStatus> workers = taskStatus.getWorkerStatus();
        if (workers == null || workers.isEmpty()) {
            return false; // 或者 true，取决于业务逻辑，这里假设至少有一个 worker
        }
        
        // 简单逻辑：只要有一个不是 COMPLETED，就返回 false
        // 注意：这里假设所有参与的 worker 都已经在 map 里了。
        // 如果 worker 是动态加入的，这个逻辑可能需要调整（比如预先定义 expectedWorkers）
        for (TaskStatus.WorkerStatus status : workers.values()) {
            if (status != TaskStatus.WorkerStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 使用分布式锁执行状态更新逻辑
     */
    private void executeWithLock(String taskId, Consumer<TaskStatus> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + taskId);
        try {
            // 尝试获取锁，等待 5秒，持有 10秒
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                try {
                    TaskStatus taskStatus = mongoTemplate.findById(taskId, TaskStatus.class);
                    if (taskStatus == null) {
                        logger.warn("Task status not found for taskId: {}", taskId);
                        return;
                    }
                    action.accept(taskStatus);
                } finally {
                    lock.unlock();
                }
            } else {
                logger.warn("Could not acquire lock for updating task status: {}", taskId);
                throw new RuntimeException("Could not acquire lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for lock", e);
        }
    }
}
