package com.souyu.common.manager;

import com.souyu.common.state.State;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 状态管理器 (Redis + MongoDB)
 * Redis: 分布式锁 + 任务元数据
 * MongoDB: 存储 State JSON 文档和报告内容
 */
@Component
public class StateManager {

    private static final Logger logger = LoggerFactory.getLogger(StateManager.class);
    private static final String TASK_METADATA_KEY_PREFIX = "bettafish:tasks:";
    private static final String LOCK_PREFIX = "bettafish:lock:";

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private RedisTemplate redisTemplate;

    @Value("${app.engine.name:default}")
    private String engineName;

    private final String streamKey= "task:prefinished:stream";

    private final ObjectMapper objectMapper;

    public StateManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private String getTaskMetadataKey() {
        return TASK_METADATA_KEY_PREFIX + engineName;
    }

    /**
     * 初始化一个新的任务状态
     */
    public String initTask() {
        return UUID.randomUUID().toString();
    }

    public String initState(String taskId, String query, String engine){
        State state = new State();
        if (taskId == null || taskId.isEmpty()){
            taskId = initTask();
        }
        state.setQuery(query);

        // 1. 保存 State 到 MongoDB
        saveStateToMongo(taskId, state);

        // 2. 保存元数据到 Redis
        TaskMetadata metadata = new TaskMetadata(taskId, "RUNNING", taskId, null);
        String redisKey = getTaskMetadataKey();
        logger.info("Initializing task {} in engine {}. Redis Key: {}, Mongo ID: {}", taskId, engineName, redisKey, taskId);
        
        RMap<String, TaskMetadata> tasks = redissonClient.getMap(redisKey);
        tasks.put(taskId, metadata);
        return taskId;
    }


    /**
     * 获取任务状态 (从 MongoDB 加载)
     */
    public State getState(String taskId) {
        String redisKey = getTaskMetadataKey();
        RMap<String, TaskMetadata> tasks = redissonClient.getMap(redisKey);
        TaskMetadata metadata = tasks.get(taskId);

        if (metadata == null) {
            logger.warn("Task metadata not found in Redis. TaskId: {}, Engine: {}, Key: {}", taskId, engineName, redisKey);
            return null;
        }

        return loadStateFromMongo(metadata.getMinioPath()); // 这里 minioPath 实际上存的是 taskId
    }


    /**
     * 获取任务运行状态(只从redis中获取数据）
     */
    public String getStatus(String taskId){
        RMap<String,TaskMetadata> tasks = redissonClient.getMap(getTaskMetadataKey());
        TaskMetadata metadata = tasks.get(taskId);
        if (metadata==null){
            return null;
        }

        return metadata.getStatus();
    }

    /**
     * 标记任务失败
     */
    public void markTaskFailed(String taskId, String errorMessage) {
        RMap<String, TaskMetadata> tasks = redissonClient.getMap(getTaskMetadataKey());
        TaskMetadata metadata = tasks.get(taskId);
        if (metadata != null) {
            metadata.setStatus("FAILED");
            metadata.setErrorMessage(errorMessage);
            tasks.put(taskId, metadata);
        }
    }

    /**
     * 线程安全地执行状态更新操作
     */
    public void executeUpdate(String taskId, Consumer<State> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + taskId+"_"+engineName);
        try {
            // 使用 watchdog 机制 (不指定 leaseTime)，避免长任务导致锁过期
            if (lock.tryLock(30, TimeUnit.SECONDS)) {
                try {
                    // 1. 获取元数据
                    RMap<String, TaskMetadata> tasks = redissonClient.getMap(getTaskMetadataKey());
                    TaskMetadata metadata = tasks.get(taskId);

                    if (metadata != null) {
                        // 2. 从 MongoDB 加载 State
                        State state = loadStateFromMongo(metadata.getMinioPath());
                        if (state != null) {
                            // 3. 修改 State
                            action.accept(state);

                            // 4. 写回 MongoDB
                            saveStateToMongo(taskId, state);

                            // 5. 更新 Redis 元数据 (如果状态变了，比如 completed)
                            if (state.isCompleted()) {
                                metadata.setStatus("COMPLETED");
                                tasks.put(taskId, metadata);
                            }
                        }
                    } else {
                        logger.warn("Task ID {} not found during update in engine: {}", taskId, engineName);
                    }
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                throw new RuntimeException("Could not acquire lock for task " + taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for lock", e);
        }
    }

    /**
     * 线程安全地执行状态更新并返回结果
     */
    public <R> R executeUpdateAndReturn(String taskId, Function<State, R> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + taskId+"_"+engineName);
        try {
            // 使用 watchdog 机制 (不指定 leaseTime)，避免长任务导致锁过期
            if (lock.tryLock(30, TimeUnit.SECONDS)) {
                try {
                    RMap<String, TaskMetadata> tasks = redissonClient.getMap(getTaskMetadataKey());
                    TaskMetadata metadata = tasks.get(taskId);

                    if (metadata != null) {
                        State state = loadStateFromMongo(metadata.getMinioPath());
                        if (state != null) {
                            R result = action.apply(state);
                            saveStateToMongo(taskId, state);

                            if (state.isCompleted()) {
                                metadata.setStatus("COMPLETED");
                                tasks.put(taskId, metadata);
                            }
                            return result;
                        }
                    }
                    return null;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                throw new RuntimeException("Could not acquire lock for task " + taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for lock", e);
        }
    }

    /**
     * 保存任意文本内容到 MongoDB (例如报告)
     * @param content 内容
     * @param objectName 目标路径 (这里作为文档的唯一标识或名称)
     */
    public void saveContentToMinio(String content, String objectName,String engineName,String taskId) {
        // 为了保持方法签名兼容，我们继续使用 saveContentToMinio 这个名字，但内部实现改为 MongoDB
        // 实际上应该重构为 saveContentToStorage
        try {
            ReportDocument doc = new ReportDocument();
            doc.setId(taskId);
            doc.setName(objectName);
            doc.setContent(content);
            doc.setTimestamp(System.currentTimeMillis());
            
            // 使用 upsert
            String collection= "reports_" + engineName;
            mongoTemplate.save(doc, collection); // 保存到 reports 集合
            logger.info("Saved report to MongoDB: {}", collection);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save content to MongoDB", e);
        }
    }

    // --- MongoDB Helpers ---

    private void saveStateToMongo(String taskId, State state) {
        try {
            StateDocument doc = new StateDocument(taskId, state);
            mongoTemplate.save(doc, engineName+"_states"); // 保存到 states 集合
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state to MongoDB", e);
        }
    }

    private State loadStateFromMongo(String taskId) {
        try {
            StateDocument doc = mongoTemplate.findById(taskId, StateDocument.class, engineName+"_states");
            return doc != null ? doc.getState() : null;
        } catch (Exception e) {
            logger.error("Failed to load state from MongoDB: {}", e.getMessage());
            return null;
        }
    }
}
