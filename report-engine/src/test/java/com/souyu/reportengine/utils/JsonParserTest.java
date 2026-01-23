package com.souyu.reportengine.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonParserTest {

    private JsonParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonParser(null, false, 3);
    }

    @Test
    void testBasicJson() {
        String jsonStr = "{\"name\": \"test\", \"value\": 123}";
        Map<String, Object> result = parser.parse(jsonStr, "基本测试", null, null);
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    void testMarkdownWrapped() {
        String jsonStr = "```json\n{\n  \"name\": \"test\",\n  \"value\": 123\n}\n```";
        Map<String, Object> result = parser.parse(jsonStr, "Markdown包裹测试", null, null);
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    void testThinkingContentRemoval() {
        String jsonStr = "<thinking>让我想想如何构造这个JSON</thinking>\n{\n  \"name\": \"test\",\n  \"value\": 123\n}";
        Map<String, Object> result = parser.parse(jsonStr, "思考内容清理测试", null, null);
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    void testMissingCommaFix() {
        String jsonStr = "{\n  \"totalWords\": 40000,\n  \"globalGuidelines\": [\n    \"重点突出技术红利分配失衡\"\n    \"详略策略：技术创新\"\n  ],\n  \"chapters\": []\n}";
        Map<String, Object> result = parser.parse(jsonStr, "缺少逗号修复测试", null, null);
        List<?> guidelines = (List<?>) result.get("globalGuidelines");
        assertEquals(2, guidelines.size());
    }

    @Test
    void testUnbalancedBrackets() {
        String jsonStr = "{\n  \"name\": \"test\",\n  \"nested\": {\n    \"value\": 123\n  }\n"; // 缺少最外层的 }
        Map<String, Object> result = parser.parse(jsonStr, "括号不平衡测试", null, null);
        assertEquals("test", result.get("name"));
        Map<?, ?> nested = (Map<?, ?>) result.get("nested");
        assertEquals(123, nested.get("value"));
    }

    @Test
    void testControlCharacterEscape() {
        String jsonStr = "{\n  \"text\": \"这是第一行\n这是第二行\",\n  \"value\": 123\n}";
        Map<String, Object> result = parser.parse(jsonStr, "控制字符转义测试", null, null);
        String text = (String) result.get("text");
        assertTrue(text.contains("第一行"));
        assertTrue(text.contains("第二行"));
    }

    @Test
    void testTrailingCommaRemoval() {
        String jsonStr = "{\n  \"name\": \"test\",\n  \"value\": 123,\n  \"items\": [1, 2, 3,],\n}";
        Map<String, Object> result = parser.parse(jsonStr, "尾随逗号测试", null, null);
        assertEquals("test", result.get("name"));
        List<?> items = (List<?>) result.get("items");
        assertEquals(3, items.size());
    }

    @Test
    void testColonEqualsFix() {
        String jsonStr = "{\n  \"name\":= \"test\",\n  \"value\": 123\n}";
        Map<String, Object> result = parser.parse(jsonStr, "冒号等号测试", null, null);
        assertEquals("test", result.get("name"));
    }

    @Test
    void testExtractFirstJson() {
        String jsonStr = "这是一些说明文字，下面是JSON：\n{\n  \"name\": \"test\",\n  \"value\": 123\n}\n后面还有一些其他文字";
        Map<String, Object> result = parser.parse(jsonStr, "提取JSON测试", null, null);
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    void testKeyAliasRecovery() {
        String jsonStr = "{\n  \"templateName\": \"test_template\",\n  \"selectionReason\": \"This is a test\"\n}";
        Map<String, Object> result = parser.parse(jsonStr, "键别名测试", List.of("template_name", "selection_reason"), null);
        assertEquals("test_template", result.get("template_name"));
        assertEquals("This is a test", result.get("selection_reason"));
    }

    @Test
    void testComplexRealWorldCase() {
        String jsonStr = "<thinking>我需要构造一个篇幅规划</thinking>\n```json\n{\n  \"totalWords\": 40000,\n  \"tolerance\": 2000,\n  \"globalGuidelines\": [\n    \"重点突出技术红利分配失衡、人才流失与职业认同危机等结构性矛盾\"\n    \"详略策略：技术创新与传统技艺的碰撞\"\n    \"案例导向：优先引用真实数据和调研\"\n  ],\n  \"chapters\": [\n    {\n      \"chapterId\": \"ch1\",\n      \"targetWords\": 5000\n    }\n  ]\n}\n```";
        Map<String, Object> result = parser.parse(jsonStr, "复杂真实案例测试", null, null);
        assertEquals(40000, result.get("totalWords"));
        assertEquals(2000, result.get("tolerance"));
        List<?> guidelines = (List<?>) result.get("globalGuidelines");
        assertEquals(3, guidelines.size());
        List<?> chapters = (List<?>) result.get("chapters");
        assertEquals(1, chapters.size());
    }

    @Test
    void testExpectedKeysValidation() {
        String jsonStr = "{\"name\": \"test\"}";
        Map<String, Object> result = parser.parse(jsonStr, "键验证测试", List.of("name", "value"), null);
        assertTrue(result.containsKey("name"));
        // 缺少 value 键，但不会抛出异常，只是记录警告
    }

    @Test
    void testWrapperKeyExtraction() {
        String jsonStr = "{\n  \"wrapper\": {\n    \"name\": \"test\",\n    \"value\": 123\n  }\n}";
        Map<String, Object> result = parser.parse(jsonStr, "包裹键测试", null, "wrapper");
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    @Test
    void testEmptyInput() {
        assertThrows(JsonParser.JsonParseError.class, () -> parser.parse("", "空输入测试", null, null));
    }

    @Test
    void testInvalidJsonAfterAllRepairs() {
        String jsonStr = "{完全不是JSON格式的内容###";
        assertThrows(JsonParser.JsonParseError.class, () -> parser.parse(jsonStr, "无法修复测试", null, null));
    }
}
