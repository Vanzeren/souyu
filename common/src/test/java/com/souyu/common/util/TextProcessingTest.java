package com.souyu.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextProcessingTest {

    @Test
    void testRemoveReasoningWithJsonObject() {
        String input = "这是我的推理过程...\\n\\n好的，这是JSON：\\n{\"key\": \"value\"}";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testRemoveReasoningWithJsonArray() {
        String input = "分析如下：[{\"item\": 1}, {\"item\": 2}]";
        String expected = "[{\"item\": 1}, {\"item\": 2}]";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testOnlyJson() {
        String input = "  {\"clean\": \"json\"}  ";
        String expected = "{\"clean\": \"json\"}";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testNoJsonBrackets() {
        String input = "这里没有任何JSON内容。";
        String expected = "这里没有任何JSON内容。";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testRemovePrefixWithoutBrackets() {
        String input = "推理：这是最终结论。";
        String expected = "这是最终结论。";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testRemovePrefixWithChineseColon() {
        String input = "分析：这是分析结果。";
        String expected = "这是分析结果。";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }
    
    @Test
    void testRemovePrefixWithEnglishKeyword() {
        String input = "Reasoning: This is the result.";
        String expected = "This is the result.";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testExplanationPrefix() {
        String input = "Explanation: Here is the data. {\"data\": 1}";
        String expected = "{\"data\": 1}";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    @Test
    void testNullInput() {
        assertEquals("", TextProcessing.removeReasoningFromOutput(null));
    }

    @Test
    void testEmptyInput() {
        assertEquals("", TextProcessing.removeReasoningFromOutput(""));
    }

    @Test
    void testBlankInput() {
        assertEquals("", TextProcessing.removeReasoningFromOutput("   \\t\\n  "));
    }
    
    @Test
    void testJsonStartsImmediately() {
        String input = "{\"key\": \"value\"} and some trailing text.";
        String expected = "{\"key\": \"value\"} and some trailing text.";
        assertEquals(expected, TextProcessing.removeReasoningFromOutput(input));
    }

    // ===== Tests for cleanJsonTags =====

    @Test
    void testCleanJsonTags_Basic() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.cleanJsonTags(input));
    }

    @Test
    void testCleanJsonTags_WithWhitespace() {
        String input = "  ```json  \n{\"key\": \"value\"}\n  ```  ";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.cleanJsonTags(input));
    }

    @Test
    void testCleanJsonTags_OnlyPrefix() {
        String input = "```json{\"key\": \"value\"}";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.cleanJsonTags(input));
    }

    @Test
    void testCleanJsonTags_OnlySuffix() {
        String input = "{\"key\": \"value\"}```";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.cleanJsonTags(input));
    }

    @Test
    void testCleanJsonTags_NoTags() {
        String input = "{\"key\": \"value\"}";
        assertEquals(input, TextProcessing.cleanJsonTags(input));
    }

    @Test
    void testCleanJsonTags_NullAndEmpty() {
        assertEquals("", TextProcessing.cleanJsonTags(null));
        assertEquals("", TextProcessing.cleanJsonTags(""));
        assertEquals("", TextProcessing.cleanJsonTags("  "));
    }

    // ===== Tests for cleanMarkdownTags =====

    @Test
    void testCleanMarkdownTags_Basic() {
        String input = "```markdown\nSome text\n```";
        String expected = "Some text";
        assertEquals(expected, TextProcessing.cleanMarkdownTags(input));
    }

    @Test
    void testCleanMarkdownTags_WithWhitespace() {
        String input = "  ```markdown  \nSome text\n  ```  ";
        String expected = "Some text";
        assertEquals(expected, TextProcessing.cleanMarkdownTags(input));
    }

    @Test
    void testCleanMarkdownTags_CaseInsensitive() {
        String input = "```Markdown\nSome text\n```";
        String expected = "Some text";
        assertEquals(expected, TextProcessing.cleanMarkdownTags(input));
    }

    @Test
    void testCleanMarkdownTags_OnlyPrefix() {
        String input = "```markdownSome text";
        String expected = "Some text";
        assertEquals(expected, TextProcessing.cleanMarkdownTags(input));
    }

    @Test
    void testCleanMarkdownTags_OnlySuffix() {
        String input = "Some text```";
        String expected = "Some text";
        assertEquals(expected, TextProcessing.cleanMarkdownTags(input));
    }

    @Test
    void testCleanMarkdownTags_NoTags() {
        String input = "Some text";
        assertEquals(input, TextProcessing.cleanMarkdownTags(input));
    }

    @Test
    void testCleanMarkdownTags_NullAndEmpty() {
        assertEquals("", TextProcessing.cleanMarkdownTags(null));
        assertEquals("", TextProcessing.cleanMarkdownTags(""));
        assertEquals("", TextProcessing.cleanMarkdownTags("  "));
    }

    // ===== Tests for fixIncompleteJson =====

    @Test
    void testFixMissingClosingBrace() {
        String input = "{\"key\": \"value\", \"data\": {\"nested_key\": 1";
        String expected = "{\"key\": \"value\", \"data\": {\"nested_key\": 1}}";
        assertEquals(expected, TextProcessing.fixIncompleteJson(input));
    }

    @Test
    void testFixMissingClosingBracket() {
        String input = "[{\"item\": 1}, {\"item\": 2";
        String expected = "[{\"item\": 1}, {\"item\": 2}]";
        assertEquals(expected, TextProcessing.fixIncompleteJson(input));
    }

    @Test
    void testFixTrailingCommaInObject() {
        String input = "{\"key\": \"value\",}";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.fixIncompleteJson(input));
    }

    @Test
    void testFixTrailingCommaInArray() {
        String input = "[1, 2, 3, ]";
        String expected = "[1, 2, 3]";
        assertEquals(expected, TextProcessing.fixIncompleteJson(input));
    }

    @Test
    void testAlreadyValidJson() {
        String input = "{\"status\": \"ok\"}";
        assertEquals(input, TextProcessing.fixIncompleteJson(input));
    }

    @Test
    void testMultipleJsonObjects() {
        String input = "{\"a\": 1}{\"b\": 2}";
        String expected = "[{\"a\": 1}, {\"b\": 2}]";
        assertEquals(expected, TextProcessing.fixIncompleteJson(input));
    }

    @Test
    void testUnfixableJunk() {
        String input = "this is not json at all";
        assertEquals("", TextProcessing.fixIncompleteJson(input));
    }
    
    @Test
    void testFixMissingOpeningBrace() {
        String input = "\"key\": \"value\"}";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, TextProcessing.fixIncompleteJson(input));
    }

    // ===== Tests for extractCleanResponse (New Version) =====

    @Test
    @SuppressWarnings("unchecked")
    void testExtractCleanResponse_StandardFlow() {
        String input = """
                好的，这是您的JSON：
                ```json
                {
                    "name": "测试",
                    "value": 123
                }
                ```
                """;
        Object result = TextProcessing.extractCleanResponse(input);
        assertTrue(result instanceof Map);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("测试", map.get("name"));
        assertEquals(123, map.get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractCleanResponse_NeedsFixing() {
        String input = "推理：这是一个不完整的对象 {\"key\": \"value\"";
        Object result = TextProcessing.extractCleanResponse(input);
        assertTrue(result instanceof Map);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("value", map.get("key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractCleanResponse_Array() {
        String input = "```json\\n[1, 2, 3]```";
        Object result = TextProcessing.extractCleanResponse(input);
        assertTrue(result instanceof List);
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals(2, list.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractCleanResponse_Failure() {
        String input = "这完全不是一个JSON";
        Object result = TextProcessing.extractCleanResponse(input);
        assertTrue(result instanceof Map);
        Map<String, Object> errorMap = (Map<String, Object>) result;
        assertTrue(errorMap.containsKey("error"));
        assertEquals("JSON解析失败", errorMap.get("error"));
        assertEquals(input, errorMap.get("raw_text"));
    }
    
    @Test
    @SuppressWarnings("unchecked")
    void testExtractCleanResponse_PerfectJson() {
        String input = "{\"perfect\": true}";
        Object result = TextProcessing.extractCleanResponse(input);
        assertTrue(result instanceof Map);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(true, map.get("perfect"));
    }
}
