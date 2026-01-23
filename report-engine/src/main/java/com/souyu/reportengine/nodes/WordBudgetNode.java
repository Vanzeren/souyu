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
import java.util.stream.Collectors;

/**
 * 章节篇幅规划节点。
 * <p>
 * 规划各章节字数与重点。
 * 输出总字数、全局写作准则以及每章/小节的 target/min/max 字数约束。
 */
@Component
public class WordBudgetNode extends BaseNode<WordBudgetNode.Input, Map<String, Object>> {

    @Autowired
    private  JsonParser jsonParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WordBudgetNode(JsonParser jsonParser) {
        super( "WordBudgetNode");
    }

    public static class Input {
        public List<TemplateSection> sections;
        public Map<String, Object> design;
        public Map<String, String> reports;
        public String forumLogs;
        public String query;
        public Map<String, Object> templateOverview;

        public Input(List<TemplateSection> sections, Map<String, Object> design, Map<String, String> reports, String forumLogs, String query, Map<String, Object> templateOverview) {
            this.sections = sections;
            this.design = design;
            this.reports = reports;
            this.forumLogs = forumLogs;
            this.query = query;
            this.templateOverview = templateOverview;
        }
    }

    @Override
    public Map<String, Object> run(Input input) {
        // 输入中除了章节骨架外，还包含布局节点输出，方便约束篇幅时参考视觉主次
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", input.query);
        payload.put("design", input.design);
        payload.put("sections", input.sections.stream().map(TemplateSection::toDict).collect(Collectors.toList()));
        
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
            throw new RuntimeException("Failed to serialize WordBudget payload", e);
        }

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(prompts.SYSTEM_PROMPT_WORD_BUDGET),
                new UserMessage(userMessage)
        ));

        String response = llmClient.streamAndCollect(prompt).block();
        Map<String, Object> plan = parseResponse(response);
        logger.info("章节字数规划已生成");
        return plan;
    }

    private Map<String, Object> parseResponse(String raw) {
        try {
            Map<String, Object> result = jsonParser.parse(
                    raw,
                    "篇幅规划",
                    List.of("totalWords", "globalGuidelines", "chapters"),
                    null
            );

            // 验证关键字段的类型
            if (!(result.get("totalWords") instanceof Number)) {
                logger.warn("篇幅规划缺少totalWords字段或类型错误，使用默认值");
                result.putIfAbsent("totalWords", 10000);
            }
            if (!(result.get("globalGuidelines") instanceof List)) {
                logger.warn("篇幅规划缺少globalGuidelines字段或类型错误，使用空列表");
                result.put("globalGuidelines", new ArrayList<>());
            }
            if (!(result.get("chapters") instanceof List) && !(result.get("chapters") instanceof Map)) {
                logger.warn("篇幅规划缺少chapters字段或类型错误，使用空列表");
                result.put("chapters", new ArrayList<>());
            }

            return result;
        } catch (JsonParser.JsonParseError exc) {
            throw new RuntimeException("篇幅规划JSON解析失败: " + exc.getMessage(), exc);
        }
    }
}
