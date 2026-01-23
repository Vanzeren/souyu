package com.souyu.reportengine.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.reportengine.config.ReportEngineConfig;
import com.souyu.reportengine.prompt.prompts;
import com.souyu.reportengine.utils.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 模板选择节点。
 * <p>
 * 综合用户查询、三引擎报告、论坛日志与本地模板库，
 * 调用LLM挑选最合适的报告骨架。
 */
@Component
public class TemplateSelectionNode extends BaseNode<TemplateSelectionNode.Input, Map<String, Object>> {

    private static final Logger logger = LoggerFactory.getLogger(TemplateSelectionNode.class);
    @Autowired
    private  ReportEngineConfig config;
    @Autowired
    private  JsonParser jsonParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateSelectionNode(TimeContextChatClient llmClient) {
        super( "TemplateSelectionNode");
    }

    public static class Input {
        public String query;
        public List<Object> reports;
        public String forumLogs;

        public Input(String query, List<Object> reports, String forumLogs) {
            this.query = query;
            this.reports = reports;
            this.forumLogs = forumLogs;
        }
    }

    @Override
    public Map<String, Object> run(Input input) {
        logger.info("开始模板选择...");

        String query = input.query != null ? input.query : "";
        List<Object> reports = input.reports != null ? input.reports : new ArrayList<>();
        String forumLogs = input.forumLogs != null ? input.forumLogs : "";

        // 获取可用模板
        List<Map<String, Object>> availableTemplates = getAvailableTemplates();

        if (availableTemplates.isEmpty()) {
            logger.info("未找到预设模板，使用内置默认模板");
            return getFallbackTemplate();
        }

        // 使用LLM进行模板选择
        try {
            Map<String, Object> llmResult = llmTemplateSelection(query, reports, forumLogs, availableTemplates);
            if (llmResult != null) {
                return llmResult;
            }
        } catch (Exception e) {
            logger.error("LLM模板选择失败: {}", e.getMessage(), e);
        }

        // 如果LLM选择失败，使用备选方案
        return getFallbackTemplate();
    }

    private Map<String, Object> llmTemplateSelection(String query, List<Object> reports, String forumLogs, List<Map<String, Object>> availableTemplates) {
        logger.info("尝试使用LLM进行模板选择...");

        // 构建模板列表
        String templateList = availableTemplates.stream()
                .map(t -> String.format("- %s: %s", t.get("name"), t.get("description")))
                .collect(Collectors.joining("\n"));

        // 构建报告内容摘要
        StringBuilder reportsSummary = new StringBuilder();
        if (!reports.isEmpty()) {
            reportsSummary.append("\n\n=== 分析引擎报告内容 ===\n");
            for (int i = 0; i < reports.size(); i++) {
                Object report = reports.get(i);
                String content;
                if (report instanceof Map) {
                    Object c = ((Map<?, ?>) report).get("content");
                    content = c != null ? c.toString() : report.toString();
                } else {
                    content = report.toString();
                }

                // 截断过长的内容，保留前1000个字符
                if (content.length() > 1000) {
                    content = content.substring(0, 1000) + "...(内容已截断)";
                }

                reportsSummary.append(String.format("\n报告%d内容:\n%s\n", i + 1, content));
            }
        }

        // 构建论坛日志摘要
        StringBuilder forumSummary = new StringBuilder();
        if (forumLogs != null && !forumLogs.trim().isEmpty()) {
            forumSummary.append("\n\n=== 三个引擎的讨论内容 ===\n");
            // 截断过长的日志内容，保留前800个字符
            String forumContent = forumLogs;
            if (forumContent.length() > 800) {
                forumContent = forumContent.substring(0, 800) + "...(讨论内容已截断)";
            }
            forumSummary.append(forumContent);
        }

        String userMessage = String.format("""
                查询内容: %s

                报告数量: %d 个分析引擎报告
                论坛日志: %s
                %s%s

                可用模板:
                %s

                请根据查询内容、报告内容和论坛日志的具体情况，选择最合适的模板。""",
                query,
                reports.size(),
                (forumLogs != null && !forumLogs.isEmpty()) ? "有" : "无",
                reportsSummary,
                forumSummary,
                templateList
        );

        // 调用LLM
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(prompts.SYSTEM_PROMPT_TEMPLATE_SELECTION),
                new UserMessage(userMessage)
        ));

        String response = llmClient.streamAndCollect(prompt).block();

        // 检查响应是否为空
        if (response == null || response.trim().isEmpty()) {
            logger.error("LLM返回空响应");
            return null;
        }

        logger.info("LLM原始响应: {}", response);

        // 尝试解析JSON响应，使用鲁棒解析器
        try {
            Map<String, Object> result = jsonParser.parse(
                    response,
                    "模板选择",
                    List.of("template_name", "selection_reason"),
                    null
            );

            // 验证选择的模板是否存在
            String selectedTemplateName = (String) result.getOrDefault("template_name", "");
            for (Map<String, Object> template : availableTemplates) {
                String templateName = (String) template.get("name");
                if (templateName.equals(selectedTemplateName) || templateName.contains(selectedTemplateName)) {
                    logger.info("LLM选择模板: {}", selectedTemplateName);
                    Map<String, Object> finalResult = new HashMap<>();
                    finalResult.put("template_name", template.get("name"));
                    finalResult.put("template_content", template.get("content"));
                    finalResult.put("selection_reason", result.getOrDefault("selection_reason", "LLM智能选择"));
                    return finalResult;
                }
            }

            logger.error("LLM选择的模板不存在: {}", selectedTemplateName);
            return null;

        } catch (JsonParser.JsonParseError e) {
            logger.error("JSON解析失败: {}", e.getMessage());
            // 尝试从文本响应中提取模板信息
            return extractTemplateFromText(response, availableTemplates);
        }
    }

    private Map<String, Object> extractTemplateFromText(String response, List<Map<String, Object>> availableTemplates) {
        logger.info("尝试从文本响应中提取模板信息");

        // 查找响应中是否包含模板名称
        for (Map<String, Object> template : availableTemplates) {
            String templateName = (String) template.get("name");
            List<String> templateNameVariants = List.of(
                    templateName,
                    templateName.replace(".md", ""),
                    templateName.replace("模板", "")
            );

            for (String variant : templateNameVariants) {
                if (response.contains(variant)) {
                    logger.info("在响应中找到模板: {}", templateName);
                    Map<String, Object> result = new HashMap<>();
                    result.put("template_name", templateName);
                    result.put("template_content", template.get("content"));
                    result.put("selection_reason", "从文本响应中提取");
                    return result;
                }
            }
        }

        return null;
    }

    private List<Map<String, Object>> getAvailableTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();
        String templateDir = config.getTemplateDir();
        if (templateDir == null) {
            templateDir = "templates"; // Default
        }

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            // 假设模板在 classpath 下，或者是一个文件系统路径
            // 这里尝试从 classpath 读取，如果 templateDir 是相对路径
            String locationPattern = "classpath*:" + templateDir + "/*.md";
            // 如果是绝对路径，可能需要 file: 前缀，这里简化处理，假设都在 resources 下
            
            // 修正：如果 templateDir 是文件系统路径，应该用 file:
            // 但通常 Spring Boot 项目模板放在 resources 下
            // 尝试读取
            Resource[] resources = resolver.getResources(locationPattern);
            
            if (resources.length == 0) {
                 // 尝试作为文件系统路径读取
                 locationPattern = "file:" + templateDir + "/*.md";
                 resources = resolver.getResources(locationPattern);
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && filename.endsWith(".md")) {
                    try {
                        String content = resource.getContentAsString(StandardCharsets.UTF_8);
                        String templateName = filename.replace(".md", "");
                        String description = extractTemplateDescription(templateName);

                        Map<String, Object> template = new HashMap<>();
                        template.put("name", templateName);
                        template.put("path", resource.getURI().toString());
                        template.put("content", content);
                        template.put("description", description);
                        templates.add(template);
                    } catch (IOException e) {
                        logger.error("读取模板文件失败 {}: {}", filename, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("获取模板列表失败: {}", e.getMessage());
        }

        return templates;
    }

    private String extractTemplateDescription(String templateName) {
        if (templateName.contains("企业品牌")) {
            return "适用于企业品牌声誉和形象分析";
        } else if (templateName.contains("市场竞争")) {
            return "适用于市场竞争格局和对手分析";
        } else if (templateName.contains("日常") || templateName.contains("定期")) {
            return "适用于日常监测和定期汇报";
        } else if (templateName.contains("政策") || templateName.contains("行业")) {
            return "适用于政策影响和行业动态分析";
        } else if (templateName.contains("热点") || templateName.contains("社会")) {
            return "适用于社会热点和公共事件分析";
        } else if (templateName.contains("突发") || templateName.contains("危机")) {
            return "适用于突发事件和危机公关";
        }
        return "通用报告模板";
    }

    private Map<String, Object> getFallbackTemplate() {
        logger.info("未找到合适模板，使用空模板让LLM自行发挥");
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("template_name", "自由发挥模板");
        fallback.put("template_content", "");
        fallback.put("selection_reason", "未找到合适的预设模板，让LLM根据内容自行设计报告结构");
        return fallback;
    }
}
