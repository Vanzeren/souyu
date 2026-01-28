package com.souyu.common.node.querynode;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.node.StateMutationNode;
import com.souyu.common.prompt.DeepSearchPrompts;
import com.souyu.common.state.Paragraph;
import com.souyu.common.state.State;
import com.souyu.common.util.TextProcessing;
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
 * 反思总结节点
 * 根据反思搜索结果更新段落总结
 */
@Component
public class ReflectionSummaryNode extends StateMutationNode<String, String> {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionSummaryNode.class);

    public ReflectionSummaryNode(TimeContextChatClient chatClient) {
        super(chatClient, "ReflectionSummaryNode");
    }

    @Override
    public String run(String inputData, Map<String, Object> kwargs) {
        try {
            logger.info("正在生成反思总结");

            // 检查 kwargs 中是否有自定义的 prompt
            String systemPrompt = DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION_SUMMARY;
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
            logger.info("成功生成反思总结");

            return processedResponse;

        } catch (Exception e) {
            logger.error("生成反思总结失败: {}", e.getMessage());
            throw new RuntimeException("生成反思总结失败", e);
        }
    }

    @Override
    public String processOutput(Object output) {
        if (!(output instanceof String)) {
            return "反思总结生成失败";
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
                String updatedContent = (String) resultMap.get("updated_paragraph_latest_state");
                if (updatedContent != null && !updatedContent.isBlank()) {
                    return updatedContent;
                }
            }

            // 如果解析失败，返回清理后的文本
            if (result instanceof Map && ((Map<?, ?>) result).containsKey("error")) {
                 return cleanedOutput;
            }
            
            return cleanedOutput;

        } catch (Exception e) {
            logger.error("处理输出失败: {}", e.getMessage());
            return "反思总结生成失败";
        }
    }

    @Override
    public State mutateState(String inputData, State state, Map<String, Object> kwargs) {
        try {
            Integer paragraphIndex = (Integer) kwargs.get("paragraph_index");
            if (paragraphIndex == null) {
                throw new IllegalArgumentException("Missing paragraph_index in kwargs");
            }

            // 生成更新后的总结
            String updatedSummary = run(inputData, kwargs);

            // 更新状态
            Paragraph paragraph = state.getParagraph(paragraphIndex);
            if (paragraph != null) {
                paragraph.getResearch().setLatestSummary(updatedSummary);
                // 注意：incrementReflection 已经在 QueryAgent 中调用了，这里不需要重复调用
                
                logger.info("已更新段落 {} 的反思总结", paragraphIndex + 1);
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
