package com.souyu.reportengine.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "report-engine")
public class ReportEngineConfig {

    private static final Logger logger = LoggerFactory.getLogger(ReportEngineConfig.class);

    // Report Engine LLM 配置
    private String apiKey;
    private String baseUrl;
    private String modelName;
    private String provider;

    // 其他引擎API（用于跨引擎修复）
    private String forumHostApiKey;
    private String forumHostBaseUrl;
    private String forumHostModelName;

    private String insightEngineApiKey;
    private String insightEngineBaseUrl;
    private String insightEngineModelName;

    private String mediaEngineApiKey;
    private String mediaEngineBaseUrl;
    private String mediaEngineModelName;

    // 通用配置
    private int maxContentLength = 200000;
    private int chapterJsonMaxAttempts = 2;
    private String templateDir = "template"; // 修改为 template，对应 src/main/resources/template
    private double apiTimeout = 900.0;
    private double maxRetryDelay = 180.0;
    private int maxRetries = 8;
    private boolean enablePdfExport = true;
    private String chartStyle = "modern";

    // MongoDB 存储相关配置
    private String outputDir = "final_reports";
    private String chapterOutputDir = "final_reports/chapters";
    private String documentIrOutputDir = "final_reports/ir";
    private String jsonErrorLogDir = "logs/json_repair_failures";
    private String logFile = "logs/report.log";

    @PostConstruct
    public void printConfig() {
        StringBuilder message = new StringBuilder();
        message.append("\n=== Report Engine 配置 ===\n");
        message.append(String.format("LLM 模型: %s\n", modelName));
        message.append(String.format("LLM Base URL: %s\n", baseUrl != null ? baseUrl : "(默认)"));
        message.append(String.format("最大内容长度: %d\n", maxContentLength));
        message.append(String.format("章节JSON最大尝试次数: %d\n", chapterJsonMaxAttempts));
        message.append(String.format("模板目录: %s\n", templateDir));
        message.append(String.format("API 超时时间: %.1f 秒\n", apiTimeout));
        message.append(String.format("最大重试间隔: %.1f 秒\n", maxRetryDelay));
        message.append(String.format("最大重试次数: %d\n", maxRetries));
        message.append(String.format("PDF 导出: %b\n", enablePdfExport));
        message.append(String.format("图表样式: %s\n", chartStyle));
        message.append(String.format("LLM API Key: %s\n", apiKey != null && !apiKey.isEmpty() ? "已配置" : "未配置"));
        
        message.append(String.format("JSON错误日志目录: %s\n", jsonErrorLogDir));
        
        message.append("=========================\n");
        logger.info(message.toString());
    }
}
