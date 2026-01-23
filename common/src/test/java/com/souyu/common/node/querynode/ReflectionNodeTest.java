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

class

ReflectionNodeTest {

    @Mock
    private TimeContextChatClient mockChatClient;

    private ReflectionNode reflectionNode;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reflectionNode = new ReflectionNode(mockChatClient);
    }

    // --- Input Validation Tests ---

    @Test
    void testValidateInput_Valid() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\",\"paragraph_latest_state\":\"Some state\"}";
        assertTrue(reflectionNode.validateInput(validInput));
    }

    @Test
    void testValidateInput_Invalid_MissingState() {
        String invalidInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\"}";
        assertFalse(reflectionNode.validateInput(invalidInput));
    }

    @Test
    void testValidateInput_Invalid_MalformedJson() {
        String invalidInput = "{\"title\":\"Test Title\",";
        assertFalse(reflectionNode.validateInput(invalidInput));
    }

    @Test
    void testValidateInput_Null() {
        assertFalse(reflectionNode.validateInput(null));
    }

    // --- Run Method Tests ---

    @Test
    void testRun_Success() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\",\"paragraph_latest_state\":\"Some state\"}";
        String llmResponse = "{\"search_query\":\"reflection query\",\"reasoning\":\"reflection reasoning\"}";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        Map<String, String> result = reflectionNode.run(validInput, Collections.emptyMap());

        assertEquals("reflection query", result.get("search_query"));
        assertEquals("reflection reasoning", result.get("reasoning"));
    }

    @Test
    void testRun_LlmApiFails_ThrowsException() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\",\"paragraph_latest_state\":\"Some state\"}";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenThrow(new RuntimeException("API Error"));

        assertThrows(RuntimeException.class, () -> {
            reflectionNode.run(validInput, Collections.emptyMap());
        });
    }

    @Test
    void testRun_LlmReturnsGarbage_FallbackToDefaultQuery() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\",\"paragraph_latest_state\":\"Some state\"}";
        String llmResponse = "this is not valid json";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        Map<String, String> result = reflectionNode.run(validInput, Collections.emptyMap());

        assertEquals("深度研究补充信息", result.get("search_query"));
        assertEquals("由于解析失败，使用默认反思搜索查询", result.get("reasoning"));
    }

    @Test
    void testRun_LlmReturnsJsonWithoutQuery_FallbackToDefaultQuery() {
        String validInput = "{\"title\":\"Test Title\",\"content\":\"Test Content\",\"paragraph_latest_state\":\"Some state\"}";
        String llmResponse = "{\"reasoning\":\"some reasoning but no query\"}";
        when(mockChatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        Map<String, String> result = reflectionNode.run(validInput, Collections.emptyMap());

        assertEquals("深度研究补充信息", result.get("search_query"));
        assertEquals("由于解析失败，使用默认反思搜索查询", result.get("reasoning"));
    }
}
