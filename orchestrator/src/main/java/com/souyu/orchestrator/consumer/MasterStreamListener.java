package com.souyu.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.forum.ForumConstants;
import com.souyu.common.forum.ForumNode;
import com.souyu.common.forum.ForumServiceRouter;
import com.souyu.common.manager.ReportDocument;
import com.souyu.common.manager.StateDocument;
import com.souyu.common.manager.TaskControlManager;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.producer.messageProducer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MasterStreamListener implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger logger = LoggerFactory.getLogger(MasterStreamListener.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;
    
    @Autowired
    private TaskStatusManager taskStatusManager;
    
    @Autowired
    private TaskControlManager taskControlManager;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private messageProducer producer;

    @Autowired
    private ForumServiceRouter forumRouter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> body = message.getValue();
        String taskId = body.get("taskId");
        String eventType = body.get("type");
        String source = body.get("source");

        if (taskId == null) return;
        
        logger.info("Orchestrator received raw message: {}", body);

        RLock lock = redissonClient.getLock("lock:orchestrator:event:" + taskId);
        lock.lock();
        try {
            // 重新从数据库获取最新状态，确保数据一致性
            TaskStatus status = taskStatusManager.getTaskStatus(taskId);
            if (status == null) return;

            logger.info("Orchestrator processing event: {} from {} for task {}", eventType, source, taskId);

            // 场景 1: Worker 完成
            if ("WORKER_COMPLETED".equals(eventType)) {
                // 更新 Worker 状态
                taskStatusManager.updateWorkerStatus(taskId, source, TaskStatus.WorkerStatus.COMPLETED);
                checkAndProceed(taskId);
            }
            // 场景 2: Worker 失败
            else if ("WORKER_FAILED".equals(eventType)) {
                String error = body.get("error");
                logger.error("Worker {} failed for task {}: {}", source, taskId, error);
                
                // 更新 Worker 状态
                taskStatusManager.updateWorkerStatus(taskId, source, TaskStatus.WorkerStatus.FAILED);
                
                // 仅取消该 Engine，而不是整个任务
                taskControlManager.sendCancelCommand(taskId, source);
                
                checkAndProceed(taskId);
            }
            // 场景 3: Forum 完成
            else if ("FORUM_COMPLETED".equals(eventType)) {
                // 更新 Forum 状态
                taskStatusManager.updateForumStatus(taskId, TaskStatus.WorkerStatus.COMPLETED);
                checkAndProceed(taskId);
            }
            // 场景 4: 报告生成完成
            else if ("REPORT_COMPLETED".equals(eventType)) {
                String reportId = body.get("reportId");
                taskStatusManager.markTaskCompleted(taskId, reportId);
                logger.info("Task {} fully completed. Report ID: {}", taskId, reportId);
                redisTemplate.delete("task:progress:" + taskId + ":*");
            }
            // 场景 5: 报告生成失败
            else if ("REPORT_FAILED".equals(eventType)) {
                String error = body.get("error");
                logger.error("Report generation failed for task {}: {}", taskId, error);
                taskStatusManager.markTaskFailed(taskId, "Report generation failed: " + error);
            }

        } catch (Exception e) {
            logger.error("Error processing event for task {}", taskId, e);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 检查所有 Worker 的状态，并决定是否推进任务
     * 支持部分成功策略：只要有一个 Worker 成功，且所有 Worker 都已结束（成功或失败），就继续。
     */
    private void checkAndProceed(String taskId) {
        TaskStatus status = taskStatusManager.getTaskStatus(taskId);
        Map<String, TaskStatus.WorkerStatus> workers = status.getWorkerStatus();
        if (workers == null) return;

        boolean allEnded = true;
        boolean anyCompleted = false;

        for (TaskStatus.WorkerStatus s : workers.values()) {
            if (s != TaskStatus.WorkerStatus.COMPLETED && s != TaskStatus.WorkerStatus.FAILED) {
                allEnded = false;
                break; // 只要有一个还在跑，就不是 allEnded
            }
            if (s == TaskStatus.WorkerStatus.COMPLETED) {
                anyCompleted = true;
            }
        }

        if (allEnded) {
            if (anyCompleted) {
                logger.info("All workers ended for task {}. Proceeding to next phase.", taskId);
                
                // 如果 Forum 已经完成，则直接生成报告
                if (status.getForumStatus() == TaskStatus.WorkerStatus.COMPLETED) {
                    startGeneratingReport(taskId);
                } else {
                    // 否则触发 Forum 进行总结
                    logger.info("Triggering final summary check for Forum on task {}", taskId);
                    triggerForumFinalCheck(taskId);

                    if (status.getStatus() != TaskStatus.Status.SUMMARIZING) {
                        taskStatusManager.updateMainStatus(taskId, TaskStatus.Status.SUMMARIZING);
                    }
                }
            } else {
                // 所有 Worker 都失败了
                logger.error("All workers failed for task {}. Marking as FAILED.", taskId);
                taskStatusManager.markTaskFailed(taskId, "All workers failed");
                taskControlManager.sendCancelCommand(taskId); // 全局取消
            }
        } else {
            logger.info("Task {} still has running workers. Waiting...", taskId);
        }
    }

    private void startGeneratingReport(String taskId) {
        TaskStatus currentStatus = taskStatusManager.getTaskStatus(taskId);
        if (currentStatus.getStatus() == TaskStatus.Status.GENERATING || 
            currentStatus.getStatus() == TaskStatus.Status.COMPLETED) {
            logger.info("Report generation already started or completed for task {}", taskId);
            return;
        }

        logger.info("Starting final report generation for task {}", taskId);
        taskStatusManager.updateMainStatus(taskId, TaskStatus.Status.GENERATING);

        try {
            List<Object> reports = getReport(taskId);
            String forumLogs = getForum(taskId);
            String query = getQuery(taskId);

            if (query == null) {
                throw new IllegalArgumentException("Query not found for task " + taskId);
            }

            // 发送异步请求到 Report Engine
            Map<String, String> request = new HashMap<>();
            request.put("taskId", taskId);
            request.put("query", query);
            request.put("reports", objectMapper.writeValueAsString(reports));
            request.put("forumLogs", forumLogs != null ? forumLogs : "");
            
            producer.sendMessage("task:report:request", request);
            logger.info("Sent report generation request to task:report:request for task {}", taskId);

        } catch (Exception e) {
            logger.error("Failed to trigger report generation for task {}", taskId, e);
            taskStatusManager.markTaskFailed(taskId, e.getMessage());
        }
    }

    /**
     * 触发 Forum 进行最终检查
     * 使用路由找到正确的节点，发送到该节点的专属 Stream
     */
    private void triggerForumFinalCheck(String taskId) {
        try {
            // 获取任务绑定的节点
            ForumNode node = forumRouter.getOrBindNode(taskId);
            if (node == null) {
                logger.error("Cannot find forum node for task {}", taskId);
                return;
            }

            // 构建节点专属的 Stream Key
            String streamKey = ForumConstants.KEY_STREAM_PREFIX + node.getNodeId();

            // 发送消息到该节点的专属 Stream
            producer.sendMessage(streamKey, Map.of(
                    "taskId", taskId,
                    "type", "FINAL_CHECK",
                    "engine", "MASTER"
            ));

            logger.info("Sent FINAL_CHECK to forum node {} for task {} via stream {}",
                    node.getNodeId(), taskId, streamKey);

        } catch (Exception e) {
            logger.error("Failed to trigger forum final check for task {}", taskId, e);
            // 降级：尝试发送到公共 forum stream（兼容旧架构）
            try {
                producer.sendMessage("forum", Map.of(
                        "taskId", taskId,
                        "type", "FINAL_CHECK",
                        "engine", "MASTER"
                ));
                logger.warn("Fallback to common forum stream for task {}", taskId);
            } catch (Exception fallbackEx) {
                logger.error("Fallback also failed for task {}", taskId, fallbackEx);
            }
        }
    }

    private List<Object> getReport(String taskId) {
        List<Object> reports = new ArrayList<>();
        String[] engineNames = {"query", "media"};
        for (String engineName : engineNames) {
            String collectionName = "reports_" + engineName;
            ReportDocument report = mongoTemplate.findById(taskId, ReportDocument.class, collectionName);
            reports.add(report != null ? report : "");
        }
        return reports;
    }

    private String getForum(String taskId){
        ReportDocument forumLog = mongoTemplate.findById(taskId, ReportDocument.class, "forum_logs");
        if (forumLog != null) {
            String content = forumLog.getContent();
            return content != null ? content : null;
        }
        return null;
    }

    private String getQuery(String taskId){
        StateDocument queryState = mongoTemplate.findById(taskId, StateDocument.class, "query_states");
        if (queryState != null && queryState.getState() != null){
            return queryState.getState().getQuery();
        }
        return null;
    }
}
