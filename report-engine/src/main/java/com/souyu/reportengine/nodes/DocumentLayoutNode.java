package com.souyu.reportengine.nodes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.reportengine.core.TemplateSection;
import com.souyu.reportengine.prompt.prompts;
import com.souyu.reportengine.utils.JsonParser;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 负责生成全局标题、目录与Hero设计。
 * <p>
 * 结合模板切片、报告摘要与论坛讨论，指导整本书的视觉与结构基调。
 */
@Component
public class DocumentLayoutNode extends BaseNode<DocumentLayoutNode.Input, Map<String, Object>> {

    @Autowired
    private JsonParser jsonParser;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public DocumentLayoutNode() {
        super("DocumentLayoutNode");
    }


    public static class Input {
        public List<TemplateSection> sections;
        public String templateMarkdown;
        public Map<String, String> reports;
        public String forumLogs;
        public String query;
        public Map<String, Object> templateOverview;

        public Input(List<TemplateSection> sections, String templateMarkdown, Map<String, String> reports, String forumLogs, String query, Map<String, Object> templateOverview) {
            this.sections = sections;
            this.templateMarkdown = templateMarkdown;
            this.reports = reports;
            this.forumLogs = forumLogs;
            this.query = query;
            this.templateOverview = templateOverview;
        }
    }

    @Override
    public Map<String, Object> run(Input input) {
        // 将模板原文、切片结构与多源报告一并喂给LLM，便于其理解层级与素材
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", input.query);

        Map<String, Object> template = new HashMap<>();
        template.put("raw", input.templateMarkdown);
        template.put("sections", input.sections.stream().map(TemplateSection::toDict).collect(Collectors.toList()));
        payload.put("template", template);

        payload.put("templateOverview", input.templateOverview != null ? input.templateOverview : Map.of(
                "title", !input.sections.isEmpty() ? input.sections.get(0).getTitle() : "",
                "chapters", input.sections.stream().map(TemplateSection::toDict).collect(Collectors.toList())
        ));
        payload.put("reports", input.reports);
        payload.put("forumLogs", input.forumLogs);

        String userMessage;
        try {
            userMessage = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DocumentLayout payload", e);
        }

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(prompts.SYSTEM_PROMPT_DOCUMENT_LAYOUT),
                new UserMessage(userMessage)
        ));

        String response = llmClient.streamAndCollect(prompt).block();
        Map<String, Object> design = parseResponse(response);
        logger.info("文档标题/目录设计已生成");
        return design;
    }

    private Map<String, Object> parseResponse(String raw) {
        try {
            Map<String, Object> result = jsonParser.parse(
                    raw,
                    "文档设计",
                    List.of("title", "tocPlan", "hero"),
                    null
            );

            // 验证关键字段的类型
            if (!(result.get("title") instanceof String)) {
                logger.warn("文档设计缺少title字段或类型错误，使用默认值");
                result.putIfAbsent("title", "未命名报告");
            }

            // 处理tocPlan字段
            Object tocPlanObj = result.get("tocPlan");
            if (!(tocPlanObj instanceof List)) {
                logger.warn("文档设计缺少tocPlan字段或类型错误，使用空列表");
                result.put("tocPlan", new ArrayList<>());
            } else {
                result.put("tocPlan", cleanTocPlanDescriptions((List<Map<String, Object>>) tocPlanObj));
            }

            if (!(result.get("hero") instanceof Map)) {
                logger.warn("文档设计缺少hero字段或类型错误，使用空对象");
                result.putIfAbsent("hero", new HashMap<>());
            }

            return result;
        } catch (JsonParser.JsonParseError exc) {
            throw new RuntimeException("文档设计JSON解析失败: " + exc.getMessage(), exc);
        }
    }

    private List<Map<String, Object>> cleanTocPlanDescriptions(List<Map<String, Object>> tocPlan) {
        List<Map<String, Object>> cleanedPlan = new ArrayList<>();
        for (Map<String, Object> entry : tocPlan) {
            if (entry == null) continue;

            if (entry.containsKey("description")) {
                Object originalDesc = entry.get("description");
                String cleanedDesc = cleanText(originalDesc);

                if (!cleanedDesc.equals(String.valueOf(originalDesc))) {
                    logger.warn("清理目录项 '{}' 的description字段中的JSON片段:\n  原文: {}...\n  清理后: {}...",
                            entry.getOrDefault("display", "unknown"),
                            String.valueOf(originalDesc).substring(0, Math.min(100, String.valueOf(originalDesc).length())),
                            cleanedDesc.substring(0, Math.min(100, cleanedDesc.length()))
                    );
                    entry.put("description", cleanedDesc);
                }
            }
            cleanedPlan.add(entry);
        }
        return cleanedPlan;
    }

    private String cleanText(Object text) {
        if (text == null || !(text instanceof String)) {
            return "";
        }

        String cleaned = (String) text;

        // 移除以逗号+空白+{开头的不完整JSON对象
        cleaned = cleaned.replaceAll(",\\s*\\{[^}]*$", "");
        // 移除以逗号+空白+[开头的不完整JSON数组
        cleaned = cleaned.replaceAll(",\\s*\\[[^\\]]*$", "");

        // 移除孤立的 { 或 [
        int openBracePos = cleaned.lastIndexOf('{');
        if (openBracePos != -1 && cleaned.lastIndexOf('}') < openBracePos) {
            cleaned = cleaned.substring(0, openBracePos).replaceAll("[,，、\\s]+$", "");
        }
        int openBracketPos = cleaned.lastIndexOf('[');
        if (openBracketPos != -1 && cleaned.lastIndexOf(']') < openBracketPos) {
            cleaned = cleaned.substring(0, openBracketPos).replaceAll("[,，、\\s]+$", "");
        }

        // 移除看起来像JSON键值对的片段
        cleaned = cleaned.replaceAll(",?\\s*\"[^\"]+\"\\s*:\\s*\"[^\"]*$", "");
        cleaned = cleaned.replaceAll(",?\\s*\"[^\"]+\"\\s*:\\s*[^,}\\]]*$", "");

        // 清理末尾的逗号和空白
        cleaned = cleaned.replaceAll("[,，、\\s]+$", "");

        return cleaned.trim();
    }
}
