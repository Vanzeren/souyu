package com.souyu.common.node.querynode;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.prompt.DeepSearchPrompts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 首次搜索节点
 * 负责为段落生成首次搜索查询
 */
@Component
public class FirstSearchNode extends QuerySearchNode {

    public FirstSearchNode(TimeContextChatClient chatClient) {
        super(chatClient, "FirstSearchNode");
    }

    @Override
    protected String getSystemPrompt(Map<String, Object> kwargs) {
        // 检查 kwargs 中是否有自定义的 prompt
        if (kwargs != null && kwargs.containsKey("system_prompt")) {
            return (String) kwargs.get("system_prompt");
        }
        return DeepSearchPrompts.SYSTEM_PROMPT_FIRST_SEARCH;
    }

    @Override
    protected Map<String, String> getDefaultQuery() {
        return Map.of(
                "search_query", "相关主题研究",
                "search_tool","basic_search_tool",
                "reasoning", "由于解析失败，使用默认搜索查询"
        );
    }

    @Override
    public boolean validateInput(String inputData) {
        if (inputData == null) return false;
        try {
            Map<String, Object> data = objectMapper.readValue(inputData, new TypeReference<>() {});
            return data.containsKey("title") && data.containsKey("content");
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
