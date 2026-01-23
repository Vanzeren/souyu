package com.souyu.reportengine.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChartRepairerTest {

    private ChartValidator validator;
    private CharRepairApi charRepairApi;
    private ChartRepairer repairer;

    @BeforeEach
    void setUp() {
        validator = new ChartValidator();
        charRepairApi = mock(CharRepairApi.class);
        repairer = new ChartRepairer(validator, charRepairApi);
    }

    @Test
    void testRepairMissingProps() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of(10, 20));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        assertTrue(result.getRepairedBlock().containsKey("props"));
        assertEquals("local", result.getMethod());
    }

    @Test
    void testRepairMissingChartType() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        widgetBlock.put("props", new HashMap<>());
        
        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of(10, 20));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        Map<String, Object> props = (Map<String, Object>) result.getRepairedBlock().get("props");
        assertEquals("bar", props.get("type"));
        assertTrue(result.getChanges().toString().contains("图表类型"));
    }

    @Test
    void testRepairMissingDatasets() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        Map<String, Object> repairedData = (Map<String, Object>) result.getRepairedBlock().get("data");
        assertTrue(repairedData.containsKey("datasets"));
        assertTrue(repairedData.get("datasets") instanceof List);
    }

    @Test
    void testRepairMissingLabels() {
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

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        Map<String, Object> repairedData = (Map<String, Object>) result.getRepairedBlock().get("data");
        assertTrue(repairedData.containsKey("labels"));
        assertEquals(3, ((List<?>) repairedData.get("labels")).size());
    }

    @Test
    void testRepairDataLengthMismatch() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B", "C", "D"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of(10, 20)); // 长度不足
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        
        Map<String, Object> repairedData = (Map<String, Object>) result.getRepairedBlock().get("data");
        List<?> datasets = (List<?>) repairedData.get("datasets");
        Map<String, Object> firstDs = (Map<String, Object>) datasets.get(0);
        List<?> dsData = (List<?>) firstDs.get("data");
        
        // 应该补充到4个元素
        assertEquals(4, dsData.size());
    }

    @Test
    void testRepairStringToNumber() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of("10", "20")); // 字符串数值
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        
        Map<String, Object> repairedData = (Map<String, Object>) result.getRepairedBlock().get("data");
        List<?> datasets = (List<?>) repairedData.get("datasets");
        Map<String, Object> firstDs = (Map<String, Object>) datasets.get(0);
        List<?> dsData = (List<?>) firstDs.get("data");
        
        // 应该转换为数值
        assertTrue(dsData.get(0) instanceof Number);
    }

    @Test
    void testRepairConstructDatasetsFromValues() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        data.put("values", List.of(10, 20)); // 使用values而不是datasets
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        
        Map<String, Object> repairedData = (Map<String, Object>) result.getRepairedBlock().get("data");
        assertTrue(repairedData.containsKey("datasets"));
        List<?> datasets = (List<?>) repairedData.get("datasets");
        assertFalse(datasets.isEmpty());
    }

    @Test
    void testNoRepairNeeded() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        
        Map<String, Object> dataset = new HashMap<>();
        dataset.put("label", "系列1");
        dataset.put("data", List.of(10, 20));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        assertEquals("none", result.getMethod());
        assertTrue(result.getChanges().isEmpty());
    }

    @Test
    void testRepairAddsDefaultLabel() {
        Map<String, Object> widgetBlock = new HashMap<>();
        widgetBlock.put("widgetType", "chart.js/bar");
        
        Map<String, Object> props = new HashMap<>();
        props.put("type", "bar");
        widgetBlock.put("props", props);

        Map<String, Object> data = new HashMap<>();
        data.put("labels", List.of("A", "B"));
        
        Map<String, Object> dataset = new HashMap<>();
        // 缺少label
        dataset.put("data", List.of(10, 20));
        data.put("datasets", List.of(dataset));
        widgetBlock.put("data", data);

        ChartRepairer.RepairResult result = repairer.repair(widgetBlock, null);
        assertTrue(result.isSuccess());
        
        Map<String, Object> repairedData = (Map<String, Object>) result.getRepairedBlock().get("data");
        List<?> datasets = (List<?>) repairedData.get("datasets");
        Map<String, Object> firstDs = (Map<String, Object>) datasets.get(0);
        
        assertTrue(firstDs.containsKey("label"));
    }
}
