package com.souyu.reportengine.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 图表验证器 - 验证Chart.js图表数据格式是否正确。
 * <p>
 * 验证规则：
 * 1. 基本结构验证：widgetType, props, data字段
 * 2. 图表类型验证：支持的图表类型
 * 3. 数据格式验证：labels和datasets结构
 * 4. 数据一致性验证：labels和datasets长度匹配
 * 5. 数值类型验证：数据值类型正确
 */
@Component
public class ChartValidator {

    // 支持的图表类型
    public static final Set<String> SUPPORTED_CHART_TYPES = Set.of(
            "line", "bar", "pie", "doughnut", "radar", "polarArea", "scatter",
            "bubble", "horizontalBar"
    );

    // 需要labels的图表类型
    public static final Set<String> LABEL_REQUIRED_TYPES = Set.of(
            "line", "bar", "radar", "polarArea", "pie", "doughnut"
    );

    // 需要数值数据的图表类型
    public static final Set<String> NUMERIC_DATA_TYPES = Set.of(
            "line", "bar", "radar", "polarArea", "pie", "doughnut"
    );

    // 需要特殊数据格式的图表类型
    public static final Map<String, Set<String>> SPECIAL_DATA_TYPES = Map.of(
            "scatter", Set.of("x", "y"),
            "bubble", Set.of("x", "y", "r")
    );

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationResult {
        private boolean isValid;
        private List<String> errors;
        private List<String> warnings;

        public boolean hasCriticalErrors() {
            return !isValid && !errors.isEmpty();
        }
    }

    public ValidationResult validate(Map<String, Object> widgetBlock) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. 基本结构验证
        if (widgetBlock == null) {
            errors.add("widget_block必须是字典类型");
            return new ValidationResult(false, errors, warnings);
        }

        // 2. 检查widgetType
        Object widgetTypeObj = widgetBlock.get("widgetType");
        if (!(widgetTypeObj instanceof String) || ((String) widgetTypeObj).isEmpty()) {
            errors.add("缺少widgetType字段或类型不正确");
            return new ValidationResult(false, errors, warnings);
        }
        String widgetType = (String) widgetTypeObj;

        // 检查是否是chart.js类型
        if (!widgetType.startsWith("chart.js")) {
            // 不是图表类型，跳过验证
            return new ValidationResult(true, errors, warnings);
        }

        // 3. 提取图表类型
        String chartType = extractChartType(widgetBlock);
        if (chartType == null) {
            errors.add("无法确定图表类型");
            return new ValidationResult(false, errors, warnings);
        }

        // 4. 检查是否支持该图表类型
        if (!SUPPORTED_CHART_TYPES.contains(chartType)) {
            warnings.add("图表类型 '" + chartType + "' 可能不被支持，将尝试降级渲染");
        }

        // 5. 验证数据结构
        Object dataObj = widgetBlock.get("data");
        if (!(dataObj instanceof Map)) {
            errors.add("data字段必须是字典类型");
            return new ValidationResult(false, errors, warnings);
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;

        // 检测是否使用了{x, y}形式的数据点（通常用于时间轴/散点）
        List<Object> datasetsForDetection = (List<Object>) data.getOrDefault("datasets", new ArrayList<>());
        boolean usesObjectPoints = datasetsForDetection.stream()
                .filter(ds -> ds instanceof Map)
                .map(ds -> (Map<String, Object>) ds)
                .anyMatch(ds -> containsObjectPoints((List<Object>) ds.get("data")));

        // 6. 根据图表类型验证数据
        if (SPECIAL_DATA_TYPES.containsKey(chartType)) {
            // 特殊数据格式（scatter, bubble）
            validateSpecialData(data, chartType, errors, warnings);
        } else {
            // 标准数据格式（labels + datasets）
            validateStandardData(data, chartType, errors, warnings, usesObjectPoints);
        }

        // 7. 验证props
        Object propsObj = widgetBlock.get("props");
        if (propsObj != null && !(propsObj instanceof Map)) {
            warnings.add("props字段应该是字典类型");
        }

        boolean isValid = errors.isEmpty();
        return new ValidationResult(isValid, errors, warnings);
    }

    public String extractChartType(Map<String, Object> widgetBlock) {
        // 1. 从props中获取
        Object propsObj = widgetBlock.get("props");
        if (propsObj instanceof Map) {
            Map<String, Object> props = (Map<String, Object>) propsObj;
            Object typeObj = props.get("type");
            if (typeObj instanceof String) {
                return ((String) typeObj).toLowerCase();
            }
        }

        // 2. 从widgetType中提取
        Object widgetTypeObj = widgetBlock.get("widgetType");
        if (widgetTypeObj instanceof String) {
            String widgetType = (String) widgetTypeObj;
            if (widgetType.contains("/")) {
                String[] parts = widgetType.split("/");
                if (parts.length > 0) {
                    String chartType = parts[parts.length - 1];
                    if (!chartType.isEmpty()) {
                        return chartType.toLowerCase();
                    }
                }
            }
        }

        // 3. 从data中获取
        Object dataObj = widgetBlock.get("data");
        if (dataObj instanceof Map) {
            Map<String, Object> data = (Map<String, Object>) dataObj;
            Object typeObj = data.get("type");
            if (typeObj instanceof String) {
                return ((String) typeObj).toLowerCase();
            }
        }

        return null;
    }

    private boolean containsObjectPoints(List<Object> dsList) {
        if (dsList == null) {
            return false;
        }
        for (Object point : dsList) {
            if (point instanceof Map) {
                Map<String, Object> pointMap = (Map<String, Object>) point;
                if (pointMap.containsKey("x") || pointMap.containsKey("y") || pointMap.containsKey("t")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateStandardData(Map<String, Object> data, String chartType, List<String> errors, List<String> warnings, boolean usesObjectPoints) {
        Object labelsObj = data.get("labels");
        Object datasetsObj = data.get("datasets");

        // 验证labels
        if (LABEL_REQUIRED_TYPES.contains(chartType)) {
            if (labelsObj == null) {
                if (usesObjectPoints) {
                    warnings.add(chartType + "类型图表缺少labels，已根据数据点渲染（使用x值）");
                } else {
                    errors.add(chartType + "类型图表必须包含labels字段");
                }
            } else if (!(labelsObj instanceof List)) {
                errors.add("labels必须是数组类型");
            } else if (((List<?>) labelsObj).isEmpty()) {
                warnings.add("labels数组为空，图表可能无法正常显示");
            }
        }

        // 验证datasets
        if (datasetsObj == null) {
            errors.add("缺少datasets字段");
            return;
        }

        if (!(datasetsObj instanceof List)) {
            errors.add("datasets必须是数组类型");
            return;
        }

        List<Object> datasets = (List<Object>) datasetsObj;
        if (datasets.isEmpty()) {
            errors.add("datasets数组为空");
            return;
        }

        // 验证每个dataset
        for (int idx = 0; idx < datasets.size(); idx++) {
            Object datasetObj = datasets.get(idx);
            if (!(datasetObj instanceof Map)) {
                errors.add("datasets[" + idx + "]必须是对象类型");
                continue;
            }
            Map<String, Object> dataset = (Map<String, Object>) datasetObj;

            // 验证data字段
            Object dsDataObj = dataset.get("data");
            if (dsDataObj == null) {
                errors.add("datasets[" + idx + "]缺少data字段");
                continue;
            }

            if (!(dsDataObj instanceof List)) {
                errors.add("datasets[" + idx + "].data必须是数组类型");
                continue;
            }

            List<Object> dsData = (List<Object>) dsDataObj;
            if (dsData.isEmpty()) {
                warnings.add("datasets[" + idx + "].data数组为空");
                continue;
            }

            // 如果是{x, y}对象形式的数据点，默认允许跳过labels长度和数值校验
            boolean objectPoints = dsData.stream()
                    .anyMatch(value -> value instanceof Map && (((Map<?, ?>) value).containsKey("x") || ((Map<?, ?>) value).containsKey("y") || ((Map<?, ?>) value).containsKey("t")));

            // 验证数据长度一致性
            if (labelsObj instanceof List && !objectPoints) {
                List<?> labels = (List<?>) labelsObj;
                if (dsData.size() != labels.size()) {
                    warnings.add("datasets[" + idx + "].data长度(" + dsData.size() + ")与labels长度(" + labels.size() + ")不匹配");
                }
            }

            // 验证数值类型
            if (NUMERIC_DATA_TYPES.contains(chartType) && !objectPoints) {
                for (int dataIdx = 0; dataIdx < dsData.size(); dataIdx++) {
                    Object value = dsData.get(dataIdx);
                    if (value != null && !(value instanceof Number)) {
                        errors.add("datasets[" + idx + "].data[" + dataIdx + "]的值'" + value + "'不是有效的数值类型");
                        break; // 只报告第一个错误
                    }
                }
            }
        }
    }

    private void validateSpecialData(Map<String, Object> data, String chartType, List<String> errors, List<String> warnings) {
        Object datasetsObj = data.get("datasets");

        if (datasetsObj == null) {
            errors.add("缺少datasets字段");
            return;
        }

        if (!(datasetsObj instanceof List)) {
            errors.add("datasets必须是数组类型");
            return;
        }

        List<Object> datasets = (List<Object>) datasetsObj;
        if (datasets.isEmpty()) {
            errors.add("datasets数组为空");
            return;
        }

        Set<String> requiredKeys = SPECIAL_DATA_TYPES.getOrDefault(chartType, Collections.emptySet());

        // 验证每个dataset
        for (int idx = 0; idx < datasets.size(); idx++) {
            Object datasetObj = datasets.get(idx);
            if (!(datasetObj instanceof Map)) {
                errors.add("datasets[" + idx + "]必须是对象类型");
                continue;
            }
            Map<String, Object> dataset = (Map<String, Object>) datasetObj;

            Object dsDataObj = dataset.get("data");
            if (dsDataObj == null) {
                errors.add("datasets[" + idx + "]缺少data字段");
                continue;
            }

            if (!(dsDataObj instanceof List)) {
                errors.add("datasets[" + idx + "].data必须是数组类型");
                continue;
            }

            List<Object> dsData = (List<Object>) dsDataObj;
            if (dsData.isEmpty()) {
                warnings.add("datasets[" + idx + "].data数组为空");
                continue;
            }

            // 验证数据点格式
            for (int dataIdx = 0; dataIdx < dsData.size(); dataIdx++) {
                Object pointObj = dsData.get(dataIdx);
                if (!(pointObj instanceof Map)) {
                    errors.add("datasets[" + idx + "].data[" + dataIdx + "]必须是对象类型（包含" + requiredKeys + "字段）");
                    break;
                }
                Map<String, Object> point = (Map<String, Object>) pointObj;

                // 检查必需的键
                Set<String> missingKeys = new HashSet<>(requiredKeys);
                missingKeys.removeAll(point.keySet());
                if (!missingKeys.isEmpty()) {
                    errors.add("datasets[" + idx + "].data[" + dataIdx + "]缺少必需字段: " + missingKeys);
                    break;
                }

                // 验证数值类型
                for (String key : requiredKeys) {
                    Object value = point.get(key);
                    if (value != null && !(value instanceof Number)) {
                        errors.add("datasets[" + idx + "].data[" + dataIdx + "]." + key + "的值'" + value + "'不是有效的数值类型");
                        break;
                    }
                }
            }
        }
    }

    public boolean canRender(Map<String, Object> widgetBlock) {
        ValidationResult result = validate(widgetBlock);
        return result.isValid();
    }
}
