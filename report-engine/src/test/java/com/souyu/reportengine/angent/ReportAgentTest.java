package com.souyu.reportengine.angent;

import com.souyu.reportengine.ReportStateManager.reportStateManager;
import com.souyu.reportengine.config.ReportEngineConfig;
import com.souyu.reportengine.core.ChapterStorage;
import com.souyu.reportengine.core.DocumentComposer;
import com.souyu.reportengine.core.TemplateSection;
import com.souyu.reportengine.nodes.ChapterGenerationNode;
import com.souyu.reportengine.nodes.DocumentLayoutNode;
import com.souyu.reportengine.nodes.TemplateSelectionNode;
import com.souyu.reportengine.nodes.WordBudgetNode;
import com.souyu.reportengine.schema.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReportAgentTest {

    @Mock
    private ReportEngineConfig config;
    @Mock
    private ChapterStorage chapterStorage;
    @Mock
    private DocumentComposer documentComposer;
    @Mock
    private Validator validator;
    @Mock
    private reportStateManager stateManager;
    @Mock
    private TemplateSelectionNode templateSelectionNode;
    @Mock
    private DocumentLayoutNode documentLayoutNode;
    @Mock
    private WordBudgetNode wordBudgetNode;
    @Mock
    private ChapterGenerationNode chapterGenerationNode;

    private ReportAgent reportAgent;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reportAgent = new ReportAgent(
                config,
                chapterStorage,
                documentComposer,
                stateManager,
                templateSelectionNode,
                documentLayoutNode,
                wordBudgetNode,
                chapterGenerationNode
        );

        // Mock config
        when(config.getMaxContentLength()).thenReturn(200000);
        when(config.getChapterJsonMaxAttempts()).thenReturn(2);
    }

    @Test
    void testGenerateReport_Success() {
        // Mock inputs
        String query = "test query";
        List<Object> reports = List.of("report1", "report2");
        String forumLogs = "logs";
        String customTemplate = "";
        BiConsumer<String, Map<String, Object>> streamHandler = mock(BiConsumer.class);

        // Mock state manager
        String reportId = "report-123";
        when(stateManager.initReportState(anyString())).thenReturn(reportId);

        // Mock template selection
        Map<String, Object> templateResult = new HashMap<>();
        templateResult.put("template_name", "Test Template");
        templateResult.put("template_content", "# Chapter 1\n## Section 1.1");
        templateResult.put("selection_reason", "Reason");
        when(templateSelectionNode.run(any(TemplateSelectionNode.Input.class))).thenReturn(templateResult);

        // Mock document layout
        Map<String, Object> layoutDesign = new HashMap<>();
        layoutDesign.put("title", "Report Title");
        when(documentLayoutNode.run(any(DocumentLayoutNode.Input.class))).thenReturn(layoutDesign);

        // Mock word budget
        Map<String, Object> wordPlan = new HashMap<>();
        wordPlan.put("totalWords", 1000);
        wordPlan.put("globalGuidelines", List.of("Guide"));
        wordPlan.put("chapters", List.of(Map.of("chapterId", "S1")));
        when(wordBudgetNode.run(any(WordBudgetNode.Input.class))).thenReturn(wordPlan);

        // Mock chapter generation
        Map<String, Object> chapterPayload = new HashMap<>();
        chapterPayload.put("chapterId", "S1");
        chapterPayload.put("title", "Chapter 1");
        chapterPayload.put("blocks", List.of(Map.of("type", "paragraph", "text", "content")));
        when(chapterGenerationNode.run(any(ChapterGenerationNode.Input.class))).thenReturn(chapterPayload);

        // Mock document composer
        Map<String, Object> documentIr = new HashMap<>();
        documentIr.put("reportId", reportId);
        when(documentComposer.buildDocument(anyString(), anyMap(), anyList())).thenReturn(documentIr);

        // Execute
        Map<String, Object> result = reportAgent.generateReport(query, reports, forumLogs, customTemplate, streamHandler);

        // Verify
        assertNotNull(result);
        assertEquals(reportId, result.get("report_id"));
        assertEquals(documentIr, result.get("document_ir"));
        
        // Verify interactions
        verify(stateManager).initReportState(query);
        verify(templateSelectionNode).run(any(TemplateSelectionNode.Input.class));
        verify(documentLayoutNode).run(any(DocumentLayoutNode.Input.class));
        verify(wordBudgetNode).run(any(WordBudgetNode.Input.class));
        verify(chapterGenerationNode, atLeastOnce()).run(any(ChapterGenerationNode.Input.class));
        verify(documentComposer).buildDocument(eq(reportId), anyMap(), anyList());
        verify(stateManager).updateHtmlContent(eq(reportId), anyString());
        
        // Verify stream events
        verify(streamHandler, atLeastOnce()).accept(eq("stage"), anyMap());
        verify(streamHandler, atLeastOnce()).accept(eq("progress"), anyMap());
    }

    @Test
    void testGenerateReport_TemplateSelectionFailure_Fallback() {
        // Mock inputs
        String query = "test query";
        List<Object> reports = Collections.emptyList();
        String forumLogs = "";
        
        // Mock state manager
        when(stateManager.initReportState(anyString())).thenReturn("report-fallback");

        // Mock template selection failure
        when(templateSelectionNode.run(any(TemplateSelectionNode.Input.class))).thenThrow(new RuntimeException("Selection failed"));

        // Mock other nodes to succeed with fallback template
        when(documentLayoutNode.run(any(DocumentLayoutNode.Input.class))).thenReturn(new HashMap<>());
        when(wordBudgetNode.run(any(WordBudgetNode.Input.class))).thenReturn(Map.of("chapters", List.of()));
        when(documentComposer.buildDocument(anyString(), anyMap(), anyList())).thenReturn(new HashMap<>());

        // Execute
        Map<String, Object> result = reportAgent.generateReport(query, reports, forumLogs, null, null);

        // Verify fallback template was used (indirectly by checking success)
        assertNotNull(result);
        verify(templateSelectionNode).run(any(TemplateSelectionNode.Input.class));
        // Should proceed to layout node even if selection failed (because of fallback)
        verify(documentLayoutNode).run(any(DocumentLayoutNode.Input.class));
    }
}
