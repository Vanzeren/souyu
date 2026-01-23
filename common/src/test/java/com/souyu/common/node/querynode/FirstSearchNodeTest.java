package com.souyu.common.node.querynode;

import com.bettafish.common.client.TimeContextChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class FirstSearchNodeTest {

    @Mock
    private TimeContextChatClient mockChatClient;

    private FirstSearchNode firstSearchNode;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        firstSearchNode = new FirstSearchNode(mockChatClient);
    }

    // --- Input Validation Tests ---

    @Test
    void testValidateInput_Valid() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\"}";
        assertTrue(firstSearchNode.validateInput(validInput));
    }

    @Test
    void testValidateInput_Invalid_MissingContent() {
        String invalidInput = "{\"title\":\"Test Title\"}";
        assertFalse(firstSearchNode.validateInput(invalidInput));
    }

    @Test
    void testValidateInput_Invalid_MalformedJson() {
        String invalidInput = "{\"title\":\"Test Title\",";
        assertFalse(firstSearchNode.validateInput(invalidInput));
    }

    @Test
    void testValidateInput_Null() {
        assertFalse(firstSearchNode.validateInput(null));
    }

    // --- Run Method Tests ---

    @Test
    void testRun_Success() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\"}";
        String llmResponse = "{\"search_query\":\"test query\",\"reasoning\":\"test reasoning\"}";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        Map<String, String> result = firstSearchNode.run(validInput, Collections.emptyMap());

        assertEquals("test query", result.get("search_query"));
        assertEquals("test reasoning", result.get("reasoning"));
    }

    @Test
    void testRun_LlmApiFails_ThrowsException() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\"}";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenThrow(new RuntimeException("API Error"));

        assertThrows(RuntimeException.class, () -> {
            firstSearchNode.run(validInput, Collections.emptyMap());
        });
    }

    @Test
    void testRun_LlmReturnsGarbage_FallbackToDefaultQuery() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\"}";
        String llmResponse = "this is not valid json";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        Map<String, String> result = firstSearchNode.run(validInput, Collections.emptyMap());

        assertEquals("相关主题研究", result.get("search_query"));
        assertEquals("由于解析失败，使用默认搜索查询", result.get("reasoning"));
    }

    @Test
    void testRun_LlmReturnsJsonWithoutQuery_FallbackToDefaultQuery() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\"}";
        String llmResponse = "{\"reasoning\":\"some reasoning but no query\"}";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        Map<String, String> result = firstSearchNode.run(validInput, Collections.emptyMap());

        assertEquals("相关主题研究", result.get("search_query"));
        assertEquals("由于解析失败，使用默认搜索查询", result.get("reasoning"));
    }
}
