package com.souyu.common.node.querynode;

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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ReportStructureNode extends StateMutationNode<String, List<ReportStructureNode.Paragraph>> {

    private static final Logger logger = LoggerFactory.getLogger(ReportStructureNode.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public ReportStructureNode(TimeContextChatClient chatClient) {
        super(chatClient, "ReportStructureNode");
    }

    @Override
    public List<Paragraph> run(String inputData, Map<String, Object> kwargs) {
        logger.info("正在生成报告结构");
        
        // 检查 kwargs 中是否有自定义的 prompt
        String systemPrompt = DeepSearchPrompts.SYSTEM_PROMPT_REPORT_STRUCTURE;
        if (kwargs != null && kwargs.containsKey("system_prompt")) {
            systemPrompt = (String) kwargs.get("system_prompt");
        }

        Prompt prompt = PromptBuilder.builder()
                .system(systemPrompt)
                .user(inputData)
                .build();
        String rawOutput = this.chatClient.streamAndCollect(prompt).block();

        // 3. 清理和解析原始输出
        Object parsedOutput = TextProcessing.extractCleanResponse(rawOutput);

        // 4. 验证和清理结构
        return processOutput(parsedOutput);
    }

    @Override
    public State mutateState(String inputData, State state, Map<String, Object> kwargs) {
        // 1. 构建 Prompt
        // 检查 kwargs 中是否有自定义的 prompt
        String systemPrompt = DeepSearchPrompts.SYSTEM_PROMPT_REPORT_STRUCTURE;
        if (kwargs != null && kwargs.containsKey("system_prompt")) {
            systemPrompt = (String) kwargs.get("system_prompt");
        }

        Prompt prompt = PromptBuilder.builder()
                .system(systemPrompt)
                .user(inputData)
                .build();

        // 2. 调用大模型
        String rawOutput = this.chatClient.streamAndCollect(prompt).block();

        // 3. 清理和解析原始输出
        Object parsedOutput = TextProcessing.extractCleanResponse(rawOutput);

        // 4. 验证和清理结构
        List<Paragraph> validatedStructure = processOutput(parsedOutput);

        // 5. 更新 State 对象
        state.setQuery(inputData);

        // FIX: Use enhanced for-loop and convert to the correct Paragraph type
        for (Paragraph nodeParagraph : validatedStructure) {
            // Create an instance of the State's Paragraph and add it
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
    public static class Paragraph {
        private String title;
        private String content;
    }
}
