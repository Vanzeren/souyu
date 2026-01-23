package com.souyu.reportengine.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChartValidatorTest {

    private ChartValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ChartValidator();
    }

    @Test
    void testValidBarChart() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("type", "widget");
        widgetBlock.put("widgetType", "chart.js/bar");
        widgetBlock.put("widgetId", "chart-001");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        props.put("title", "销售数据");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("一月", "二月", "三月"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "销售额");
        dataset.put("data", List.of(100, 200, 150));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void testValidLineChart() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("type", "widget");
        widgetBlock.put("widgetType", "chart.js/line");
        widgetBlock.put("widgetId", "chart-002");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "line");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("周一", "周二", "周三"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "访问量");
        dataset.put("data", List.of(50, 75, 60));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertTrue(result.isValid());
    }

    @Test
    void testValidPieChart() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/pie");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "pie");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B", "C"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("data", List.of(30, 40, 30));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertTrue(result.isValid());
    }

    @Test
    void testMissingWidgetType() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("props", new HashMap<>());
        widgetBlock.put("data", new HashMap<>());

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("widgetType"));
    }

    @Test
    void testMissingDataField() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("data"));
    }

    @Test
    void testMissingDatasets() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("datasets"));
    }

    @Test
    void testEmptyDatasets() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        data.put("datasets", new ArrayList<>());
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("空"));
    }

    @Test
    void testMissingLabelsForBarChart() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of(10, 20, 30));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("labels"));
    }

    @Test
    void testInvalidDataType() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of("abc", "def")); // 应该是数值
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("数值类型"));
    }

    @Test
    void testDataLengthMismatchWarning() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B", "C"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of(10, 20)); // 长度不匹配
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        // 长度不匹配是警告，不是错误
        assertFalse(result.getWarnings().isEmpty());
        assertTrue(result.getWarnings().get(0).contains("不匹配"));
    }

    @Test
    void testScatterChart() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/scatter");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "scatter");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "数据点");
        
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(Map.of("x", 10, "y", 20));
        points.add(Map.of("x", 15, "y", 25));
        dataset.put("data", points);
        
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        assertTrue(result.isValid());
    }

    @Test
    void testNonChartWidget() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "custom/widget");
        widgetBlock.put("props", new HashMap<>());
        widgetBlock.put("data", new HashMap<>());

        ChartValidator.ValidationResult result = validator.validate(widgetBlock);
        // 非chart.js类型，跳过验证，返回valid
        assertTrue(result.isValid());
    }
}
