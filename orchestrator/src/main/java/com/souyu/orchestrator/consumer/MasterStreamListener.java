package com.souyu.orchestrator.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.manager.ReportDocument;
import com.souyu.common.manager.StateDocument;
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
    private RedissonClient redissonClient;
    
    @Autowired
    private messageProducer producer;

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
            TaskStatus status = taskStatusManager.getTaskStatus(taskId);
            if (status == null) return;

            logger.info("Orchestrator processing event: {} from {} for task {}", eventType, source, taskId);

            if ("WORKER_COMPLETED".equals(eventType)) {
                boolean allWorkersDone = checkAllWorkersDone(status);
                
                if (allWorkersDone) {
                    logger.info("All workers completed for task {}. Checking Forum status...", taskId);
                    
                    if (status.getForumStatus() == TaskStatus.WorkerStatus.COMPLETED) {
                        startGeneratingReport(taskId);
                    } else {
                        logger.info("Triggering final summary check for Forum on task {}", taskId);
                        producer.sendMessage("forum", Map.of(
                                "taskId", taskId,
                                "type", "FINAL_CHECK",
                                "engine", "MASTER"
                        ));
                        
                        if (status.getStatus() != TaskStatus.Status.SUMMARIZING) {
                            taskStatusManager.updateMainStatus(taskId, TaskStatus.Status.SUMMARIZING);
                        }
                    }
                }
            }
            else if ("FORUM_COMPLETED".equals(eventType)) {
                if (checkAllWorkersDone(status)) {
                    startGeneratingReport(taskId);
                } else {
                    logger.info("Forum completed, but waiting for other workers for task {}", taskId);
                }
            }
            else if ("REPORT_COMPLETED".equals(eventType)) {
                // 报告生成完成，任务结束
                String reportId = body.get("reportId");
                taskStatusManager.markTaskCompleted(taskId, reportId);
                logger.info("Task {} fully completed. Report ID: {}", taskId, reportId);
                redisTemplate.delete("task:progress:" + taskId + ":*");
            }

        } catch (Exception e) {
            logger.error("Error processing event for task {}", taskId, e);
        } finally {
            lock.unlock();
        }
    }
    
    private boolean checkAllWorkersDone(TaskStatus status) {
        Map<String, TaskStatus.WorkerStatus> workers = status.getWorkerStatus();
        if (workers == null) return false;
        for (TaskStatus.WorkerStatus s : workers.values()) {
            if (s != TaskStatus.WorkerStatus.COMPLETED) return false;
        }
        return true;
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
