package com.souyu.common.node.querynode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.node.StateMutationNode;
import com.souyu.common.prompt.DeepSearchPrompts;
import com.souyu.common.state.State;
import com.souyu.common.util.PromptBuilder;
import com.souyu.common.util.TextProcessing;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ReportStructureNode  {

    private static final Logger logger = LoggerFactory.getLogger(ReportStructureNode.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    @Autowired
    private TimeContextChatClient chatClient;

    public State mutateState(String inputData, State state, Map<String, Object> kwargs,String... toolsNames) {
        // 1. 构建 Prompt
        String systemSearchingPromptTemplate =DeepSearchPrompts.SYSTEM_PROMPT_REPORT_SEARCHING_STRUCTURE;
        if (kwargs != null && kwargs.containsKey("system_prompt")) {
            systemSearchingPromptTemplate = (String) kwargs.get("system_prompt");
        } 
        PromptTemplate searchingTemplate =new PromptTemplate(systemSearchingPromptTemplate);
        String systemSearchingPrompt = searchingTemplate.create(Map.of("output_schema", DeepSearchPrompts.OUT_PUT_SCHEMA_REPORT_STRUCTURE_SEARCHING)).getContents();
        Prompt searchingPrompt = PromptBuilder.builder()
                .system(systemSearchingPrompt)
                .user(inputData)
                .build();

        String reasoning = "";

        try {
            // 使用 TimeContextChatClient 的 callWithFunctions 方法
            // 这样可以复用时间注入逻辑，并且支持 Function Calling
            ChatResponse response = chatClient.callWithFunctions(
                    searchingPrompt, 
                    toolsNames // 使用动态工具列表
            );

            // 记录 Reasoning (思考过程)
            reasoning = response.getResult().getOutput().getContent();
            if (reasoning != null && !reasoning.isBlank()) {
                logger.info("Reasoning (思考过程):\n{}", reasoning);
            }

        } catch (NonTransientAiException e) {
            logger.error("由于内容安全风险，跳过该段落搜索: {}", e.getMessage());
        }
        
        String systemPromptTemplate = DeepSearchPrompts.SYSTEM_PROMPT_REPORT_STRUCTURE;
        if (kwargs != null && kwargs.containsKey("system_prompt")) {
            systemPromptTemplate = (String) kwargs.get("system_prompt");
        }
        
        PromptTemplate promptTemplate = new PromptTemplate(systemPromptTemplate);
        String systemPrompt = promptTemplate.create(Map.of("output_schema", DeepSearchPrompts.OUTPUT_SCHEMA_REPORT_STRUCTURE)).getContents();

        String finalInput = inputData;
        if (reasoning != null && !reasoning.isBlank()) {
            finalInput = "User Query: " + inputData + "\n\nBriefing:\n" + reasoning;
        }

        Prompt prompt = PromptBuilder.builder()
                .system(systemPrompt)
                .user(finalInput)
                .build();

        // 2. 调用大模型
        String rawOutput = this.chatClient.streamAndCollect(prompt).block();

        // 3. 清理和解析原始输出
        Object parsedOutput = TextProcessing.extractCleanResponse(rawOutput);

        // 4. 验证和清理结构
        List<Paragraph> validatedStructure = processOutput(parsedOutput);

        // 5. 更新 State 对象
        state.setQuery(inputData);

        for (Paragraph nodeParagraph : validatedStructure) {
            state.addParagraph(nodeParagraph.getTitle(), nodeParagraph.getContent());
        }

        state.updateTimestamp();

        logger.info("State updated with {} paragraphs.", validatedStructure.size());

        return state;
    }

    public List<Paragraph> processOutput(Object parsedOutput) {
        List<Paragraph> reportStructure;

        if (parsedOutput instanceof Map map && map.containsKey("error")) {
            logger.warn("解析失败，将使用默认结构: {}", map.get("raw_text"));
            return generateDefaultStructure();
        }

        try {
            if (parsedOutput instanceof List) {
                reportStructure = mapper.convertValue(parsedOutput, new TypeReference<>() {
                });
            } else if (parsedOutput instanceof Map) {
                Paragraph p = mapper.convertValue(parsedOutput, Paragraph.class);
                reportStructure = List.of(p);
            } else {
                logger.warn("无法识别的输出类型，将使用默认结构");
                return generateDefaultStructure();
            }
        } catch (IllegalArgumentException e) {
            logger.warn("JSON结构与Paragraph类不匹配，将使用默认结构", e);
            return generateDefaultStructure();
        }

        List<Paragraph> validatedStructure = new ArrayList<>();
        for (Paragraph paragraph : reportStructure) {
            if (paragraph == null) {
                logger.warn("一个段落是空的，跳过");
                continue;
            }
            if (paragraph.getTitle() == null || paragraph.getTitle().isBlank() ||
                    paragraph.getContent() == null || paragraph.getContent().isBlank()) {
                logger.warn("段落缺少标题或内容，跳过: {}", paragraph);
                continue;
            }
            validatedStructure.add(paragraph);
        }

        if (validatedStructure.isEmpty()) {
            logger.warn("没有有效的段落结构，使用默认结构");
            return generateDefaultStructure();
        }

        logger.info("成功验证 {} 个段落结构", validatedStructure.size());
        return validatedStructure;
    }

    private List<Paragraph> generateDefaultStructure() {
        return List.of(new Paragraph("默认标题", "由于无法生成报告结构，这是一个默认段落。"));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true) // 忽略未知字段
    public static class Paragraph {
        private String title;
        private String content;
    }
}
