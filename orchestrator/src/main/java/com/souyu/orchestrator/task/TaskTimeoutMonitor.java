package com.souyu.orchestrator.task;

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
import com.souyu.orchestrator.client.MediaEngineClient;
import com.souyu.orchestrator.client.QueryEngineClient;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@EnableScheduling
public class TaskTimeoutMonitor {

    private static final Logger logger = LoggerFactory.getLogger(TaskTimeoutMonitor.class);
    private static final int TIMEOUT_MINUTES = 15;
    private static final int MAX_RETRIES = 3;

    @Autowired
    private TaskStatusManager taskStatusManager;
    
    @Autowired
    private TaskControlManager taskControlManager;

    @Autowired
    private QueryEngineClient queryEngineClient;

    @Autowired
    private MediaEngineClient mediaEngineClient;

    @Autowired
    private messageProducer producer;
    
    @Autowired
    private MongoTemplate mongoTemplate;
    
    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ForumServiceRouter forumRouter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 每分钟检查一次卡死任务
     */
    @Scheduled(fixedRate = 60000)
    public void checkStuckTasks() {
        logger.debug("Checking for stuck tasks...");
        
        // 1. 检查卡在 RESEARCHING 的任务
        List<TaskStatus> researchingTasks = taskStatusManager.findStuckTasks(TaskStatus.Status.RESEARCHING, TIMEOUT_MINUTES);
        for (TaskStatus task : researchingTasks) {
            processTaskWithLock(task, this::handleResearchingTimeout);
        }

        // 2. 检查卡在 SUMMARIZING 的任务
        List<TaskStatus> summarizingTasks = taskStatusManager.findStuckTasks(TaskStatus.Status.SUMMARIZING, TIMEOUT_MINUTES);
        for (TaskStatus task : summarizingTasks) {
            processTaskWithLock(task, this::handleSummarizingTimeout);
        }
        
        // 3. 检查卡在 GENERATING 的任务
        List<TaskStatus> generatingTasks = taskStatusManager.findStuckTasks(TaskStatus.Status.GENERATING, TIMEOUT_MINUTES);
        for (TaskStatus task : generatingTasks) {
            processTaskWithLock(task, this::handleGeneratingTimeout);
        }
    }
    
    private void processTaskWithLock(TaskStatus task, java.util.function.Consumer<TaskStatus> handler) {
        String taskId = task.getTaskId();
        RLock lock = redissonClient.getLock("lock:orchestrator:event:" + taskId);
        try {
            // 尝试获取锁，等待 500ms，持有 10s
            if (lock.tryLock(500, 10000, TimeUnit.MILLISECONDS)) {
                try {
                    // 再次检查状态，防止在等待锁的过程中状态已变更
                    TaskStatus currentStatus = taskStatusManager.getTaskStatus(taskId);
                    if (currentStatus != null) {
                        handler.accept(currentStatus);
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                logger.warn("Could not acquire lock for task {} during timeout check. Skipping.", taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for lock for task {}", taskId);
        } catch (Exception e) {
            logger.error("Error processing timeout for task {}", taskId, e);
        }
    }

    private void handleResearchingTimeout(TaskStatus task) {
        Map<String, TaskStatus.WorkerStatus> workers = task.getWorkerStatus();

        // 策略优化：不再因为单个 Worker 失败就直接失败整个任务
        // 而是先尝试重试，重试耗尽后尝试“部分成功”继续执行

        if (task.getRetryCount() >= MAX_RETRIES) {
            // 检查是否有任意 Worker 完成
            boolean anyWorkerCompleted = workers.values().stream()
                    .anyMatch(status -> status == TaskStatus.WorkerStatus.COMPLETED);

            if (anyWorkerCompleted) {
                logger.warn("Task {} stuck/failed in RESEARCHING and exceeded max retries. Proceeding with partial results.", task.getTaskId());
                // 推进到下一阶段 (SUMMARIZING)
                taskStatusManager.updateMainStatus(task.getTaskId(), TaskStatus.Status.SUMMARIZING);

                // 触发 Forum (下一阶段任务)
                triggerForumFinalCheck(task.getTaskId());
                return;
            }

            // 如果所有 Worker 都没完成，才标记为失败
            logger.error("Task {} stuck in RESEARCHING and exceeded max retries. Marking as FAILED.", task.getTaskId());
            taskStatusManager.markTaskFailed(task.getTaskId(), "Timeout/Failure in RESEARCHING phase after " + MAX_RETRIES + " retries");
            return;
        }

        logger.warn("Task {} stuck or has failed workers in RESEARCHING. Attempting recovery (Retry {}/{})", task.getTaskId(), task.getRetryCount() + 1, MAX_RETRIES);
        taskStatusManager.incrementRetryCount(task.getTaskId());

        // 重新触发未完成（包括 FAILED）的 Worker
        String query = getQuery(task.getTaskId());
        if (query == null) {
             logger.error("Cannot recover task {}: Query not found", task.getTaskId());
             return;
        }
        
        Map<String, String> request = Map.of("query", query, "taskId", task.getTaskId());

        if (workers.get("query") != TaskStatus.WorkerStatus.COMPLETED) {
            logger.info("Resubmitting Query Engine task for {}", task.getTaskId());
            // 关键修复：重试前必须撤销取消标记，否则 Worker 启动即死
            taskControlManager.revokeCancelCommand(task.getTaskId(), "query");
            try {
                queryEngineClient.submitQuery(request);
            } catch (Exception e) {
                logger.error("Failed to resubmit Query Engine task", e);
            }
        }

        if (workers.get("media") != TaskStatus.WorkerStatus.COMPLETED) {
            logger.info("Resubmitting Media Engine task for {}", task.getTaskId());
            // 关键修复：重试前必须撤销取消标记
            taskControlManager.revokeCancelCommand(task.getTaskId(), "media");
            try {
                mediaEngineClient.submitMediaSearch(request);
            } catch (Exception e) {
                logger.error("Failed to resubmit Media Engine task", e);
            }
        }
    }

    private void handleSummarizingTimeout(TaskStatus task) {
        // 检查 Forum 是否失败
        if (task.getForumStatus() == TaskStatus.WorkerStatus.FAILED) {
            logger.warn("Task {} Forum failed. Skipping Forum and forcing report generation.", task.getTaskId());
            taskStatusManager.updateMainStatus(task.getTaskId(), TaskStatus.Status.GENERATING);
            triggerReportGeneration(task.getTaskId());
            return;
        }

        if (task.getRetryCount() >= MAX_RETRIES) {
            // 策略：如果 Forum 一直挂，可以降级，跳过 Forum 直接生成报告
            logger.warn("Task {} stuck in SUMMARIZING. Skipping Forum and forcing report generation.", task.getTaskId());
            taskStatusManager.updateMainStatus(task.getTaskId(), TaskStatus.Status.GENERATING);
            triggerReportGeneration(task.getTaskId());
            return;
        }

        logger.warn("Task {} stuck in SUMMARIZING. Retrying FINAL_CHECK (Retry {}/{})", task.getTaskId(), task.getRetryCount() + 1, MAX_RETRIES);
        taskStatusManager.incrementRetryCount(task.getTaskId());

        triggerForumFinalCheck(task.getTaskId());
    }
    
    private void handleGeneratingTimeout(TaskStatus task) {
        if (task.getRetryCount() >= MAX_RETRIES) {
            logger.error("Task {} stuck in GENERATING. Marking as FAILED.", task.getTaskId());
            taskStatusManager.markTaskFailed(task.getTaskId(), "Timeout in GENERATING phase");
            return;
        }
        
        logger.warn("Task {} stuck in GENERATING. Resending generation request (Retry {}/{})", task.getTaskId(), task.getRetryCount() + 1, MAX_RETRIES);
        taskStatusManager.incrementRetryCount(task.getTaskId());
        
        triggerReportGeneration(task.getTaskId());
    }
    
    private void triggerReportGeneration(String taskId) {
        try {
            String query = getQuery(taskId);
            List<Object> reports = getReport(taskId);
            String forumLogs = getForum(taskId);
            
            if (query == null) return;

            Map<String, String> request = new HashMap<>();
            request.put("taskId", taskId);
            request.put("query", query);
            request.put("reports", objectMapper.writeValueAsString(reports));
            request.put("forumLogs", forumLogs != null ? forumLogs : "");
            
            producer.sendMessage("task:report:request", request);
        } catch (Exception e) {
            logger.error("Failed to trigger report generation for task {}", taskId, e);
        }
    }

    private String getQuery(String taskId){
        StateDocument queryState = mongoTemplate.findById(taskId, StateDocument.class, "query_states");
        if (queryState != null && queryState.getState() != null){
            return queryState.getState().getQuery();
        }
        return null;
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
}
