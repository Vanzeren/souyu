package com.souyu.common.node.querynode;

import com.bettafish.common.client.TimeContextChatClient;
import com.souyu.common.state.Paragraph;
import com.bettafish.common.state.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class FirstSummaryNodeTest {

    @Mock
    private TimeContextChatClient chatClient;

    private FirstSummaryNode firstSummaryNode;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        firstSummaryNode = new FirstSummaryNode(chatClient);
    }

    @Test
    void testValidateInput_Valid() {
        String json = "{\"title\":\"Test\",\"content\":\"Content\",\"search_query\":\"Query\",\"search_results\":[]}";
        assertTrue(firstSummaryNode.validateInput(json));
    }

    @Test
    void testValidateInput_Invalid() {
        String json = "{\"title\":\"Test\"}"; // Missing fields
        assertFalse(firstSummaryNode.validateInput(json));
        assertFalse(firstSummaryNode.validateInput("Not JSON"));
    }

    @Test
    void testRun_Success() {
        // Mock LLM response
        String llmResponse = "{\"paragraph_latest_state\": \"This is a summary.\"}";
        when(chatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        String input = "{\"title\":\"Test\",\"content\":\"Content\",\"search_query\":\"Query\",\"search_results\":[]}";
        String result = firstSummaryNode.run(input, null);

        assertEquals("This is a summary.", result);
    }

    @Test
    void testRun_WithReasoning() {
        // Mock LLM response with reasoning
        String llmResponse = "Here is the JSON:\n```json\n{\"paragraph_latest_state\": \"Summary with reasoning.\"}\n```";
        when(chatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        String input = "{\"title\":\"Test\",\"content\":\"Content\",\"search_query\":\"Query\",\"search_results\":[]}";
        String result = firstSummaryNode.run(input, null);

        assertEquals("Summary with reasoning.", result);
    }

    @Test
    void testMutateState_Success() {
        // Mock LLM response
        String llmResponse = "{\"paragraph_latest_state\": \"Updated Summary\"}";
        when(chatClient.streamAndCollect(any(Prompt.class))).thenReturn(Mono.just(llmResponse));

        // Prepare State
        State state = new State();
        state.addParagraph("Title", "Content");
        
        String input = "{\"title\":\"Test\",\"content\":\"Content\",\"search_query\":\"Query\",\"search_results\":[]}";
        
        // Execute
        State updatedState = firstSummaryNode.mutateState(input, state, Map.of("paragraph_index", 0));

        // Verify
        Paragraph p = updatedState.getParagraph(0);
        assertEquals("Updated Summary", p.getResearch().getLatestSummary());
    }
}
