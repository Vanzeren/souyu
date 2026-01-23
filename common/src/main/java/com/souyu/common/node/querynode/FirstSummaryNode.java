package com.souyu.common.node.querynode;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.node.StateMutationNode;
import com.souyu.common.prompt.DeepSearchPrompts;
import com.souyu.common.state.Paragraph;
import com.souyu.common.state.State;
import com.souyu.common.util.TextProcessing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 首次总结节点
 * 根据搜索结果生成段落首次总结
 */
@Component
public class FirstSummaryNode extends StateMutationNode<String, String> {

    private static final Logger logger = LoggerFactory.getLogger(FirstSummaryNode.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public FirstSummaryNode(TimeContextChatClient chatClient) {
        super(chatClient, "FirstSummaryNode");
    }

    @Override
    public boolean validateInput(String inputData) {
        if (inputData == null) return false;
        try {
            Map<String, Object> data = objectMapper.readValue(inputData, new TypeReference<>() {});
            return data.containsKey("title") && data.containsKey("content") && 
                   data.containsKey("search_query") && data.containsKey("search_results");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    @Override
    public String run(String inputData, Map<String, Object> kwargs) {
        try {
            if (!validateInput(inputData)) {
                throw new IllegalArgumentException("输入数据格式错误");
            }

            logger.info("正在生成首次段落总结");

            // 检查 kwargs 中是否有自定义的 prompt
            String systemPrompt = DeepSearchPrompts.SYSTEM_PROMPT_FIRST_SUMMARY;
            if (kwargs != null && kwargs.containsKey("system_prompt")) {
                systemPrompt = (String) kwargs.get("system_prompt");
            }

            List<Message> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(inputData)
            );
            Prompt prompt = new Prompt(messages);

            String response = chatClient.streamAndCollect(prompt).block();

            String processedResponse = processOutput(response);
            logger.info("成功生成首次段落总结");
            return processedResponse;

        } catch (Exception e) {
            logger.error("生成首次总结失败: {}", e.getMessage());
            throw new RuntimeException("生成首次总结失败", e);
        }
    }

    @Override
    public String processOutput(Object output) {
        if (!(output instanceof String)) {
            return "段落总结生成失败";
        }
        String outputStr = (String) output;

        try {
            // 清理响应文本
            String cleanedOutput = TextProcessing.removeReasoningFromOutput(outputStr);
            cleanedOutput = TextProcessing.cleanJsonTags(cleanedOutput);

            logger.info("清理后的输出: {}", cleanedOutput);

            // 解析JSON
            Object result = TextProcessing.extractCleanResponse(cleanedOutput);
            
            if (result instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) result;
                String paragraphContent = (String) resultMap.get("paragraph_latest_state");
                if (paragraphContent != null && !paragraphContent.isBlank()) {
                    return paragraphContent;
                }
            }

            // 如果提取失败，或者不是JSON，尝试直接返回清理后的文本
            if (result instanceof Map && ((Map<?, ?>) result).containsKey("error")) {
                 return cleanedOutput;
            }
            
            return cleanedOutput;

        } catch (Exception e) {
            logger.error("处理输出失败: {}", e.getMessage());
            return "段落总结生成失败";
        }
    }

    @Override
    public State mutateState(String inputData, State state, Map<String, Object> kwargs) {
        try {
            Integer paragraphIndex = (Integer) kwargs.get("paragraph_index");
            if (paragraphIndex == null) {
                throw new IllegalArgumentException("Missing paragraph_index in kwargs");
            }

            // 生成总结
            String summary = run(inputData, kwargs);

            // 更新状态
            Paragraph paragraph = state.getParagraph(paragraphIndex);
            if (paragraph != null) {
                paragraph.getResearch().setLatestSummary(summary);
                logger.info("已更新段落 {} 的首次总结", paragraphIndex + 1);
            } else {
                throw new IllegalArgumentException("段落索引 " + paragraphIndex + " 超出范围");
            }

            state.updateTimestamp();
            return state;

        } catch (Exception e) {
            logger.error("状态更新失败: {}", e.getMessage());
            throw new RuntimeException("状态更新失败", e);
        }
    }
}
