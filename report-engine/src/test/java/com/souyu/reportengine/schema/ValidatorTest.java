package com.souyu.reportengine.schema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

    private final Validator validator = new Validator();

    @Test
    void testValidateChapter_Valid() {
        Map<String, Object> chapter = createValidChapter();
        Validator.Result result = validator.validateChapter(chapter);
        assertTrue(result.isValid(), "Valid chapter should pass validation");
        assertTrue(result.getErrors().isEmpty(), "Valid chapter should have no errors");
    }

    @Test
    void testValidateChapter_MissingRequiredFields() {
        Map<String, Object> chapter = new HashMap<>();
        // Missing chapterId, title, anchor, order, blocks
        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains("missing chapter.chapterId"));
        assertTrue(result.getErrors().contains("missing chapter.title"));
        assertTrue(result.getErrors().contains("missing chapter.anchor"));
        assertTrue(result.getErrors().contains("missing chapter.order"));
        assertTrue(result.getErrors().contains("missing chapter.blocks"));
    }

    @Test
    void testValidateChapter_EmptyBlocks() {
        Map<String, Object> chapter = createValidChapter();
        chapter.put("blocks", new ArrayList<>());
        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains("chapter.blocks必须是非空数组"));
    }

    @Test
    void testValidateBlock_InvalidType() {
        Map<String, Object> chapter = createValidChapter();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> invalidBlock = new HashMap<>();
        invalidBlock.put("type", "unknownType");
        blocks.add(invalidBlock);
        chapter.put("blocks", blocks);

        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("type 不被支持")));
    }

    @Test
    void testValidateHeadingBlock_MissingFields() {
        Map<String, Object> chapter = createValidChapter();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> headingBlock = new HashMap<>();
        headingBlock.put("type", "heading");
        // Missing level, text, anchor
        blocks.add(headingBlock);
        chapter.put("blocks", blocks);

        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("level 必须是整数")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("text 缺失")));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("anchor 缺失")));
    }

    @Test
    void testValidateParagraphBlock_InvalidInlines() {
        Map<String, Object> chapter = createValidChapter();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> paraBlock = new HashMap<>();
        paraBlock.put("type", "paragraph");
        paraBlock.put("inlines", new ArrayList<>()); // Empty inlines
        blocks.add(paraBlock);
        chapter.put("blocks", blocks);

        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("inlines 必须是非空数组")));
    }

    @Test
    void testValidateEngineQuoteBlock_InvalidEngine() {
        Map<String, Object> chapter = createValidChapter();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> quoteBlock = new HashMap<>();
        quoteBlock.put("type", "engineQuote");
        quoteBlock.put("engine", "invalidEngine");
        quoteBlock.put("title", "Some Title");
        quoteBlock.put("blocks", List.of(createValidParagraphBlock()));
        blocks.add(quoteBlock);
        chapter.put("blocks", blocks);

        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("engine 取值非法")));
    }

    @Test
    void testValidateEngineQuoteBlock_TitleMismatch() {
        Map<String, Object> chapter = createValidChapter();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> quoteBlock = new HashMap<>();
        quoteBlock.put("type", "engineQuote");
        quoteBlock.put("engine", "insight");
        quoteBlock.put("title", "Wrong Title"); // Should be "Insight Agent"
        quoteBlock.put("blocks", List.of(createValidParagraphBlock()));
        blocks.add(quoteBlock);
        chapter.put("blocks", blocks);

        Validator.Result result = validator.validateChapter(chapter);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("title 必须与engine一致")));
    }

    // Helper methods to create valid objects

    private Map<String, Object> createValidChapter() {
        Map<String, Object> chapter = new HashMap<>();
        chapter.put("chapterId", "ch001");
        chapter.put("title", "Test Chapter");
        chapter.put("anchor", "test-chapter");
        chapter.put("order", 1);
        
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(createValidParagraphBlock());
        chapter.put("blocks", blocks);
        
        return chapter;
    }

    private Map<String, Object> createValidParagraphBlock() {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "paragraph");
        
        List<Map<String, Object>> inlines = new ArrayList<>();
        Map<String, Object> run = new HashMap<>();
        run.put("text", "Hello World");
        inlines.add(run);
        
        block.put("inlines", inlines);
        return block;
    }
}
