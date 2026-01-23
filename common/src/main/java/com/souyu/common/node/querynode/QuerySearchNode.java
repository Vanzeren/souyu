package com.souyu.common.node.querynode;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.node.AbstractNode;
import com.souyu.common.util.TextProcessing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

public abstract class QuerySearchNode extends AbstractNode<String, Map<String, String>> {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    public QuerySearchNode(TimeContextChatClient chatClient, String nodeName) {
        super(chatClient, nodeName);
    }

    // 修改为接受 kwargs 参数，以便根据上下文动态选择 Prompt
    protected abstract String getSystemPrompt(Map<String, Object> kwargs);

    protected abstract Map<String, String> getDefaultQuery();

    @Override
    public Map<String, String> run(String inputData, Map<String, Object> kwargs) {
        try {
            if (!validateInput(inputData)) {
                throw new IllegalArgumentException("输入数据格式不正确");
            }

            logInfo("正在生成搜索查询...");

            // 传递 kwargs 给 getSystemPrompt
            String systemPrompt = getSystemPrompt(kwargs);

            List<Message> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(inputData)
            );
            Prompt prompt = new Prompt(messages);

            String response = chatClient.streamAndCollect(prompt).block();

            Map<String, String> processedResponse = processOutput(response);
            logInfo("生成搜索查询: " + processedResponse.getOrDefault("search_query", "N/A"));
            return processedResponse;

        } catch (Exception e) {
            logError("生成搜索查询失败: " + e.getMessage());
            throw new RuntimeException("生成搜索查询失败", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> processOutput(Object output) {
        if (!(output instanceof String)) {
            logError("输出类型不是字符串，使用默认查询。");
            return getDefaultQuery();
        }
        String outputStr = (String) output;

        try {
            String cleanedOutput = TextProcessing.cleanJsonTags(outputStr);
            cleanedOutput = TextProcessing.removeReasoningFromOutput(cleanedOutput);

            logInfo("清理后的输出: " + cleanedOutput);

            Object result;
            try {
                result = objectMapper.readValue(cleanedOutput, new TypeReference<Map<String, String>>() {});
                logInfo("JSON解析成功");
            } catch (JsonProcessingException e) {
                logError("JSON解析失败: " + e.getMessage() + "，尝试使用更强的提取方法...");
                result = TextProcessing.extractCleanResponse(cleanedOutput);
                if (result instanceof Map && ((Map<?, ?>) result).containsKey("error")) {
                    logError("强力提取失败，尝试修复JSON...");
                    String fixedJson = TextProcessing.fixIncompleteJson(cleanedOutput);
                    if (fixedJson != null && !fixedJson.isEmpty()) {
                        try {
                            result = objectMapper.readValue(fixedJson, new TypeReference<Map<String, String>>() {});
                            logInfo("JSON修复成功");
                        } catch (JsonProcessingException ex) {
                            logError("JSON修复失败，使用默认查询。");
                            return getDefaultQuery();
                        }
                    } else {
                        logError("无法修复JSON，使用默认查询。");
                        return getDefaultQuery();
                    }
                }
            }

            if (!(result instanceof Map)) {
                logError("解析结果不是Map类型，使用默认查询。");
                return getDefaultQuery();
            }

            Map<String, String> resultMap = (Map<String, String>) result;
            String searchQuery = resultMap.get("search_query");

            if (searchQuery == null || searchQuery.isBlank()) {
                logWarning("未找到搜索查询，使用默认查询。");
                return getDefaultQuery();
            }

            return Map.of(
                    "search_query", resultMap.getOrDefault("search_query", ""),
                    "search_tool",resultMap.getOrDefault("search_tool",""),
                    "reasoning", resultMap.getOrDefault("reasoning", "")
            );

        } catch (Exception e) {
            logError("处理输出失败: " + e.getMessage());
            return getDefaultQuery();
        }
    }
}
