package com.souyu.reportengine.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.manager.ReportDocument;
import com.souyu.common.manager.StateDocument;
import com.souyu.reportengine.angent.ReportAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> body = message.getValue();
        String taskId = body.get("taskId");

        // 检查 Redis 计数器（确认是否所有 Worker 都已发送过 DECR）
        String countStr = redisTemplate.opsForValue().get("task:count:" + taskId);

//        if ("0".equals(countStr)) {
            logger.info("Master 收到所有通知，开始为任务 " + taskId + " 生成最终报告...");

            try {
                // 1. 获取所有必要数据
                List<Object> reports = getReport(taskId);
                String forumLogs = getForum(taskId);
                String query = getQuery(taskId);

                if (query == null) {
                    throw new IllegalArgumentException("未找到任务 " + taskId + " 的查询主题 (query)");
                }

                // 2. 调用 ReportAgent 生成报告
                Map<String, Object> reportResult = reportAgent.generateReport(
                        query,           // 查询主题
                        reports,         // 分析引擎报告
                        forumLogs != null ? forumLogs : "",       // 论坛日志
                        null,            // 自定义模板（null 表示自动选择）
                        (eventType, payload) -> {  // 流式事件回调
                            // 处理流式事件，可以发送到前端或记录日志
                            logger.info("报告生成事件: " + eventType + " - " + payload);
                        }
                );

                // 3. 保存生成的报告到 MongoDB
                saveGeneratedReport(taskId, reportResult);

                // 4. 更新任务状态为完成
                updateTaskStatus(taskId, "completed", reportResult.get("report_id").toString());

                logger.info("任务 " + taskId + " 报告生成完成，报告ID: " + reportResult.get("report_id"));

            } catch (Exception e) {
                logger.error("生成报告失败: " + e.getMessage());
                e.printStackTrace();

                // 更新任务状态为失败
                updateTaskStatus(taskId, "failed", e.getMessage());
            } finally {
                // 处理完后清理 Redis 数据
                redisTemplate.delete("task:count:" + taskId);
                redisTemplate.delete("task:progress:" + taskId + ":*");
            }
//        }
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

    /**
     * 保存生成的报告到 MongoDB
     */
    private void saveGeneratedReport(String taskId, Map<String, Object> reportResult) {
        try {
            ReportDocument reportDoc = new ReportDocument();
            reportDoc.setId(taskId);
            reportDoc.setName(reportResult.get("report_id").toString());
            // 这里假设 htmlContent 是我们想保存的主要内容，或者你可以根据需要调整
            reportDoc.setContent(reportResult.get("html_content").toString());
            reportDoc.setTimestamp(System.currentTimeMillis());

            // 保存到 reports_final 集合
            mongoTemplate.save(reportDoc, "reports_final");

            System.out.println("报告已保存到 MongoDB，集合: reports_final");
        } catch (Exception e) {
            System.err.println("保存报告到 MongoDB 失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 更新任务状态
     */
    private void updateTaskStatus(String taskId, String status, String message) {
        try {
            // 这里我们可能需要一个新的 TaskStatusDocument 类，或者复用 TaskMetadata 如果合适
            // 但考虑到 TaskMetadata 主要是 Redis 用的，这里我们还是用 Document 或者新建一个类
            // 为了简单起见，这里还是用 Document，或者你可以新建一个 TaskStatusDocument
            // 既然你让我把 StateManager 中的类提出来，那我就假设你希望尽量用强类型
            // 不过 TaskStatus 在 StateManager 里没有对应的内部类，所以我还是用 Document 保持原样，或者你可以指示我新建一个
            // 既然你只说了那几个数据类，我就只改那几个。
            
            // 但是为了保持一致性，我可以用 TaskMetadata 如果它符合结构，但 TaskMetadata 没有 updatedAt
            // 所以还是用 Document 比较灵活，或者新建一个类。
            // 既然你没让我新建 TaskStatus 类，我就保留 Document 的用法，但是尽量规范化。
            
            org.bson.Document taskStatus = new org.bson.Document();
            taskStatus.put("taskId", taskId);
            taskStatus.put("status", status);
            taskStatus.put("message", message);
            taskStatus.put("updatedAt", new java.util.Date());

            mongoTemplate.save(taskStatus, "task_status");

            System.out.println("任务状态更新: " + taskId + " -> " + status);
        } catch (Exception e) {
            System.err.println("更新任务状态失败: " + e.getMessage());
        }
    }
}
