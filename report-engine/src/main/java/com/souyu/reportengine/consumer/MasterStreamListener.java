package com.souyu.reportengine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.manager.ReportDocument;
import com.souyu.common.manager.StateDocument;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.producer.messageProducer;
import com.souyu.reportengine.angent.ReportAgent;
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
    private ReportAgent reportAgent;
    
    @Autowired
    private TaskStatusManager taskStatusManager;

    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private messageProducer producer; // 用于发送指令给 Forum

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> body = message.getValue();
        String taskId = body.get("taskId");
        String eventType = body.get("type");
        String source = body.get("source");

        if (taskId == null) return;
        
        logger.info("Master received raw message: {}", body);

        RLock lock = redissonClient.getLock("lock:master:event:" + taskId);
        lock.lock();
        try {
            // 重新从数据库获取最新状态，确保数据一致性
            TaskStatus status = taskStatusManager.getTaskStatus(taskId);
            if (status == null) return;

            logger.info("Master processing event: {} from {} for task {}", eventType, source, taskId);

            // 场景 1: Worker 完成
            if ("WORKER_COMPLETED".equals(eventType)) {
                // 检查是否所有 Worker 都完成了
                boolean allWorkersDone = checkAllWorkersDone(status);
                
                if (allWorkersDone) {
                    logger.info("All workers completed for task {}. Checking Forum status...", taskId);
                    
                    // 检查 Forum 是否已经完成了总结
                    if (status.getForumStatus() == TaskStatus.WorkerStatus.COMPLETED) {
                        // 如果 Forum 已经完了（可能是自动触发的），直接生成报告
                        startGeneratingReport(taskId);
                    } else {
                        // 如果 Forum 还没完，或者处于 PENDING/RUNNING
                        // 发送指令给 Forum 进行“最终总结检查”
                        logger.info("Triggering final summary check for Forum on task {}", taskId);
                        producer.sendMessage("forum", Map.of(
                                "taskId", taskId,
                                "type", "FINAL_CHECK", // 特殊指令
                                "engine", "MASTER"
                        ));
                        
                        // 更新主状态为 SUMMARIZING (如果还没更新)
                        if (status.getStatus() != TaskStatus.Status.SUMMARIZING) {
                            taskStatusManager.updateMainStatus(taskId, TaskStatus.Status.SUMMARIZING);
                        }
                    }
                }
            }
            // 场景 2: Forum 完成 (可能是自动触发，也可能是响应 FINAL_CHECK)
            else if ("FORUM_COMPLETED".equals(eventType)) {
                // 检查 Worker 是否也都完了
                if (checkAllWorkersDone(status)) {
                    startGeneratingReport(taskId);
                } else {
                    logger.info("Forum completed, but waiting for other workers for task {}", taskId);
                }
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
        // 防止重复生成
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

            Map<String, Object> reportResult = reportAgent.generateReport(
                    query,
                    reports,
                    forumLogs != null ? forumLogs : "",
                    null,
                    (eventType, payload) -> logger.info("Report event: {} - {}", eventType, payload)
            );

            saveGeneratedReport(taskId, reportResult);
            String reportId = reportResult.get("report_id").toString();
            taskStatusManager.markTaskCompleted(taskId, reportId);
            logger.info("Report generated successfully for task {}. Report ID: {}", taskId, reportId);

        } catch (Exception e) {
            logger.error("Failed to generate report for task {}", taskId, e);
            taskStatusManager.markTaskFailed(taskId, e.getMessage());
        } finally {
             // 清理资源
             redisTemplate.delete("task:progress:" + taskId + ":*");
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

    private void saveGeneratedReport(String taskId, Map<String, Object> reportResult) {
        try {
            ReportDocument reportDoc = new ReportDocument();
            reportDoc.setId(taskId);
            reportDoc.setName(reportResult.get("report_id").toString());
            reportDoc.setContent(reportResult.get("html_content").toString());
            reportDoc.setTimestamp(System.currentTimeMillis());
            mongoTemplate.save(reportDoc, "reports_final");
        } catch (Exception e) {
            logger.error("Failed to save report to MongoDB", e);
            throw e;
        }
    }
}
