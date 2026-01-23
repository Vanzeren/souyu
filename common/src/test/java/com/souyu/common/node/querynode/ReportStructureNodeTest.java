package com.souyu.common.node.querynode;

import com.bettafish.common.client.TimeContextChatClient;
import com.bettafish.common.state.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ReportStructureNodeTest {

    @Mock
    private TimeContextChatClient chatClient;

    private ReportStructureNode node;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        node = new ReportStructureNode(chatClient, "TestNode");
    }

    @Test
    void testMutateState_Success() {
        // 模拟 AI 返回的 JSON
        String jsonResponse = """
                [
                    {"title": "第一章", "content": "这是第一章的内容"},
                    {"title": "第二章", "content": "这是第二章的内容"}
                ]
                """;

        // Mock chatClient 的行为
        when(chatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(jsonResponse));

        // 准备输入和状态
        String inputData = "写一份关于Java的报告";
        State state = new State();
        Map<String, Object> kwargs = new HashMap<>();

        // 执行节点
        State updatedState = node.mutateState(inputData, state, kwargs);

        // 验证状态更新
        assertEquals(inputData, updatedState.getQuery());
        assertEquals(2, updatedState.getTotalParagraphsCount());
        
        assertEquals("第一章", updatedState.getParagraph(0).getTitle());
        assertEquals("这是第一章的内容", updatedState.getParagraph(0).getContent());
        
        assertEquals("第二章", updatedState.getParagraph(1).getTitle());
        assertEquals("这是第二章的内容", updatedState.getParagraph(1).getContent());
    }

    @Test
    void testMutateState_InvalidJson_FallbackToDefault() {
        // 模拟 AI 返回无效的 JSON
        String invalidResponse = "这不是一个JSON";

        when(chatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(invalidResponse));

        State state = new State();
        State updatedState = node.mutateState("query", state, new HashMap<>());

        // 验证是否使用了默认结构
        assertEquals(1, updatedState.getTotalParagraphsCount());
        assertEquals("默认标题", updatedState.getParagraph(0).getTitle());
    }

    @Test
    void testMutateState_EmptyResponse() {
        when(chatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(""));

        State state = new State();
        State updatedState = node.mutateState("query", state, new HashMap<>());

        // 验证是否使用了默认结构
        assertEquals(1, updatedState.getTotalParagraphsCount());
        assertEquals("默认标题", updatedState.getParagraph(0).getTitle());
    }
}
