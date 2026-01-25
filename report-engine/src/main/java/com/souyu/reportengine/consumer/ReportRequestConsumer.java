package com.souyu.reportengine.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.manager.ReportDocument;
import com.souyu.common.producer.messageProducer;
import com.souyu.reportengine.angent.ReportAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReportRequestConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger logger = LoggerFactory.getLogger(ReportRequestConsumer.class);

    @Autowired
    private ReportAgent reportAgent;

    @Autowired
    private MongoTemplate mongoTemplate;
    
    @Autowired
    private messageProducer producer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> body = message.getValue();
        String taskId = body.get("taskId");
        
        if (taskId == null) return;

        logger.info("Received report generation request for task {}", taskId);

        try {
            String query = body.get("query");
            String reportsJson = body.get("reports");
            String forumLogs = body.get("forumLogs");
            
            List<Object> reports = objectMapper.readValue(reportsJson, new TypeReference<List<Object>>() {});

            // 调用 ReportAgent 生成报告
            Map<String, Object> reportResult = reportAgent.generateReport(
                    query,
                    reports,
                    forumLogs,
                    null,
                    (eventType, payload) -> logger.info("Report progress [{}]: {} - {}", taskId, eventType, payload)
            );

            // 保存报告
            saveGeneratedReport(taskId, reportResult);
            
            // 发送完成事件
            String reportId = reportResult.get("report_id").toString();
            Map<String, String> event = new HashMap<>();
            event.put("taskId", taskId);
            event.put("type", "REPORT_COMPLETED");
            event.put("source", "report-engine");
            event.put("reportId", reportId);
            
            producer.sendMessage("task:events:stream", event);
            logger.info("Report generation completed for task {}. Sent REPORT_COMPLETED event.", taskId);

        } catch (Exception e) {
            logger.error("Failed to generate report for task {}", taskId, e);
            // 这里可以发送一个 REPORT_FAILED 事件，或者由 Orchestrator 的超时机制处理
        }
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
