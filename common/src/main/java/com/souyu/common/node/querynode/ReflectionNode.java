package com.souyu.common.node.querynode;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.prompt.DeepSearchPrompts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 反思节点
 * 负责反思段落并生成新搜索查询
 */
@Component
public class ReflectionNode extends QuerySearchNode {

    public ReflectionNode(TimeContextChatClient chatClient) {
        super(chatClient, "ReflectionNode");
    }

    @Override
    protected String getSystemPrompt(Map<String, Object> kwargs) {
        // 检查 kwargs 中是否有自定义的 prompt
        if (kwargs != null && kwargs.containsKey("system_prompt")) {
            return (String) kwargs.get("system_prompt");
        }
        return DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION;
    }

    @Override
    protected Map<String, String> getDefaultQuery() {
        return Map.of(
                "search_query", "深度研究补充信息",
                "reasoning", "由于解析失败，使用默认反思搜索查询"
        );
    }

    @Override
    public boolean validateInput(String inputData) {
        if (inputData == null) return false;
        try {
            Map<String, Object> data = objectMapper.readValue(inputData, new TypeReference<>() {});
            return data.containsKey("title") && data.containsKey("content") && data.containsKey("paragraph_latest_state");
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
