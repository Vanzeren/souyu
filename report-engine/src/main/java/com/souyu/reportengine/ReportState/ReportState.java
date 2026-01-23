package com.souyu.reportengine.ReportState;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@NoArgsConstructor
@Document(collection = "report_states")
public class ReportState {

    // 基本信息
    @Id
    private String taskId;
    private String query;
    private String status = "pending"; // pending, processing, completed, failed
    private String errorMessage;

    // 输入数据
    private String queryEngineReport = "";
    private String mediaEngineReport = "";
    private String insightEngineReport = "";
    private String forumLogs = "";

    // 处理结果
    private String selectedTemplate = "";
    private String htmlContent = "";

    // 元数据
    private ReportMetadata metadata = new ReportMetadata();

    public ReportState(String taskId, String query) {
        this.taskId = taskId;
        this.query = query;
        this.metadata.setQuery(query);
    }

    public void markProcessing() {
        this.status = "processing";
    }

    public void markCompleted() {
        this.status = "completed";
    }

    public void markFailed(String errorMessage) {
        this.status = "failed";
        this.errorMessage = errorMessage;
    }

    public boolean isCompleted() {
        return "completed".equals(this.status) && this.htmlContent != null && !this.htmlContent.isEmpty();
    }

    public double getProgress() {
        if ("completed".equals(this.status)) {
            return 100.0;
        } else if ("processing".equals(this.status)) {
            double progress = 0.0;
            if (this.selectedTemplate != null && !this.selectedTemplate.isEmpty()) {
                progress += 30.0;
            }
            if (this.htmlContent != null && !this.htmlContent.isEmpty()) {
                progress += 70.0;
            }
            return progress;
        } else {
            return 0.0;
        }
    }

    @Data
    @NoArgsConstructor
    public static class ReportMetadata {
        private String query = "";
        private String templateUsed = "";
        private double generationTime = 0.0;
        private String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
    }
}
