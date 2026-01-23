package com.souyu.reportengine.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 图表修复器 - 尝试修复图表数据。
 * <p>
 * 修复策略：
 * 1. 本地规则修复：修复常见问题
 * 2. API修复：使用LLM修复复杂问题
 * 3. 验证修复结果：确保修复后能正常渲染
 */
@Component
public class ChartRepairer {

    private static final Logger logger = LoggerFactory.getLogger(ChartRepairer.class);
    private final ChartValidator validator;
    private final CharRepairApi charRepairApi;
    private final Map<String, RepairResult> resultCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ChartRepairer(ChartValidator validator, CharRepairApi charRepairApi) {
        this.validator = validator;
        this.charRepairApi = charRepairApi;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RepairResult {
        private boolean success;
        private Map<String, Object> repairedBlock;
        private String method; // 'none', 'local', 'api'
        private List<String> changes;

        public boolean hasChanges() {
            return changes != null && !changes.isEmpty();
        }
    }

    public String buildCacheKey(Map<String, Object> widgetBlock) {
        String widgetId = "";
        if (widgetBlock != null) {
            Object idObj = widgetBlock.getOrDefault("widgetId", widgetBlock.get("id"));
            if (idObj != null) {
                widgetId = idObj.toString();
            }
        }
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(widgetBlock);
        } catch (JsonProcessingException e) {
            serialized = String.valueOf(widgetBlock);
        }
        
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(serialized.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return widgetId + ":" + sb;
        } catch (NoSuchAlgorithmException e) {
            return widgetId + ":" + serialized.hashCode();
        }
    }

    public RepairResult repair(Map<String, Object> widgetBlock, ChartValidator.ValidationResult validationResult) {
        String cacheKey = buildCacheKey(widgetBlock);

        RepairResult cached = resultCache.get(cacheKey);
        if (cached != null) {
            // 返回缓存的深拷贝，避免外部修改影响缓存
            // 这里简化为直接返回，实际应深拷贝
            return cached; 
        }

        // 1. 如果没有验证结果，先验证
        if (validationResult == null) {
            validationResult = validator.validate(widgetBlock);
        }

        // 2. 尝试本地修复（即使验证通过也尝试，因为可能有警告）
        logger.info("尝试本地修复图表");
        RepairResult localResult = repairLocally(widgetBlock);

        // 3. 验证修复结果
        if (localResult.hasChanges()) {
            ChartValidator.ValidationResult repairedValidation = validator.validate(localResult.getRepairedBlock());
            if (repairedValidation.isValid()) {
                logger.info("本地修复成功: {}", localResult.getChanges());
                resultCache.put(cacheKey, localResult);
                return localResult;
            } else {
                logger.warn("本地修复后仍然无效: {}", repairedValidation.getErrors());
            }
        }

        // 4. 如果本地修复失败且有严重错误，尝试API修复
        List<BiFunction<Map<String, Object>, List<String>, Map<String, Object>>> llmRepairFns = charRepairApi.createLlmRepairFunctions();
        if (validationResult.hasCriticalErrors() && !llmRepairFns.isEmpty()) {
            logger.info("本地修复失败，尝试API修复");
            RepairResult apiResult = repairWithApi(widgetBlock, validationResult, llmRepairFns);

            if (apiResult.isSuccess()) {
                // 验证修复结果
                ChartValidator.ValidationResult repairedValidation = validator.validate(apiResult.getRepairedBlock());
                if (repairedValidation.isValid()) {
                    logger.info("API修复成功: {}", apiResult.getChanges());
                    resultCache.put(cacheKey, apiResult);
                    return apiResult;
                } else {
                    logger.warn("API修复后仍然无效: {}", repairedValidation.getErrors());
                }
            }
        }

        // 5. 如果验证通过，返回原始或修复后的数据
        if (validationResult.isValid()) {
            if (localResult.hasChanges()) {
                resultCache.put(cacheKey, localResult);
                return localResult;
            } else {
                RepairResult result = new RepairResult(true, widgetBlock, "none", Collections.emptyList());
                resultCache.put(cacheKey, result);
                return result;
            }
        }

        // 6. 所有修复都失败，返回原始数据
        logger.warn("所有修复尝试失败，保持原始数据");
        RepairResult result = new RepairResult(false, widgetBlock, "none", Collections.emptyList());
        resultCache.put(cacheKey, result);
        return result;
    }

    private RepairResult repairLocally(Map<String, Object> widgetBlock) {
        Map<String, Object> repaired = new HashMap<>(widgetBlock); // 浅拷贝，需注意深层修改
        // 为了安全起见，应该进行深拷贝。这里简化处理，假设后续逻辑会创建新的子对象。
        // 实际项目中建议使用深拷贝工具。
        
        List<String> changes = new ArrayList<>();

        // 1. 确保基本结构存在
        if (!repaired.containsKey("props") || !(repaired.get("props") instanceof Map)) {
            repaired.put("props", new HashMap<>());
            changes.add("添加缺失的props字段");
        }

        if (!repaired.containsKey("data") || !(repaired.get("data") instanceof Map)) {
            repaired.put("data", new HashMap<>());
            changes.add("添加缺失的data字段");
        }

        // 2. 确保图表类型存在
        String chartType = validator.extractChartType(repaired);
        Map<String, Object> props = (Map<String, Object>) repaired.get("props");

        if (chartType == null) {
            // 尝试从widgetType推断
            Object widgetTypeObj = repaired.get("widgetType");
            if (widgetTypeObj instanceof String && ((String) widgetTypeObj).contains("/")) {
                String[] parts = ((String) widgetTypeObj).split("/");
                chartType = parts[parts.length - 1].toLowerCase();
                props.put("type", chartType);
                changes.add("从widgetType推断图表类型: " + chartType);
            } else {
                // 默认使用bar类型
                chartType = "bar";
                props.put("type", chartType);
                changes.add("设置默认图表类型: bar");
            }
        } else if (!props.containsKey("type") || props.get("type") == null) {
            // chart_type存在但props中没有type字段，需要添加
            props.put("type", chartType);
            changes.add("将推断的图表类型添加到props: " + chartType);
        }

        // 3. 修复数据结构
        Map<String, Object> data = (Map<String, Object>) repaired.get("data");

        // 确保datasets存在
        if (!data.containsKey("datasets") || !(data.get("datasets") instanceof List)) {
            data.put("datasets", new ArrayList<>());
            changes.add("添加缺失的datasets字段");
        }

        List<Object> datasets = (List<Object>) data.get("datasets");

        // 如果datasets为空但data中有其他数据，尝试构造datasets
        if (datasets.isEmpty()) {
            List<Map<String, Object>> constructed = tryConstructDatasets(data);
            if (constructed != null) {
                data.put("datasets", constructed);
                changes.add("从data中构造datasets");
                datasets = (List<Object>) data.get("datasets"); // 更新引用
            } else if (data.containsKey("labels") && data.get("labels") instanceof List && !((List<?>) data.get("labels")).isEmpty()) {
                // 如果有labels但没有数据，创建一个空dataset
                List<?> labels = (List<?>) data.get("labels");
                Map<String, Object> emptyDataset = new HashMap<>();
                emptyDataset.put("label", "数据");
                emptyDataset.put("data", Collections.nCopies(labels.size(), 0));
                data.put("datasets", new ArrayList<>(List.of(emptyDataset)));
                changes.add("根据labels创建默认dataset（使用零值）");
                datasets = (List<Object>) data.get("datasets"); // 更新引用
            }
        }

        // 确保labels存在（如果需要）
        if (ChartValidator.LABEL_REQUIRED_TYPES.contains(chartType)) {
            if (!data.containsKey("labels") || !(data.get("labels") instanceof List)) {
                // 尝试根据datasets长度生成labels
                if (!datasets.isEmpty()) {
                    Object firstDsObj = datasets.get(0);
                    if (firstDsObj instanceof Map) {
                        Map<?, ?> firstDs = (Map<?, ?>) firstDsObj;
                        Object dsDataObj = firstDs.get("data");
                        if (dsDataObj instanceof List) {
                            List<?> dsData = (List<?>) dsDataObj;
                            List<String> labels = new ArrayList<>();
                            for (int i = 0; i < dsData.size(); i++) {
                                labels.add("项目 " + (i + 1));
                            }
                            data.put("labels", labels);
                            changes.add("生成" + dsData.size() + "个默认labels");
                        }
                    }
                }
            }
        }

        // 4. 修复datasets中的数据
        for (int idx = 0; idx < datasets.size(); idx++) {
            Object datasetObj = datasets.get(idx);
            if (!(datasetObj instanceof Map)) {
                continue;
            }
            Map<String, Object> dataset = (Map<String, Object>) datasetObj;

            // 确保有data字段
            if (!dataset.containsKey("data") || !(dataset.get("data") instanceof List)) {
                dataset.put("data", new ArrayList<>());
                changes.add("为datasets[" + idx + "]添加空data数组");
            }

            // 确保有label
            if (!dataset.containsKey("label")) {
                dataset.put("label", "系列 " + (idx + 1));
                changes.add("为datasets[" + idx + "]添加默认label");
            }

            // 修复数据长度不匹配
            Object labelsObj = data.get("labels");
            Object dsDataObj = dataset.get("data");
            if (labelsObj instanceof List && dsDataObj instanceof List) {
                List<?> labels = (List<?>) labelsObj;
                List<Object> dsData = (List<Object>) dsDataObj;
                if (dsData.size() < labels.size()) {
                    // 数据不够，补null
                    List<Object> newData = new ArrayList<>(dsData);
                    newData.addAll(Collections.nCopies(labels.size() - dsData.size(), null));
                    dataset.put("data", newData);
                    changes.add("datasets[" + idx + "]数据长度不足，补充null");
                } else if (dsData.size() > labels.size()) {
                    // 数据过多，截断
                    dataset.put("data", new ArrayList<>(dsData.subList(0, labels.size())));
                    changes.add("datasets[" + idx + "]数据长度过长，截断");
                }
            }

            // 转换非数值数据为数值（如果可能）
            if (ChartValidator.NUMERIC_DATA_TYPES.contains(chartType)) {
                List<Object> dsData = (List<Object>) dataset.get("data");
                boolean converted = false;
                List<Object> newData = new ArrayList<>();
                for (Object value : dsData) {
                    if (value == null) {
                        newData.add(null);
                        continue;
                    }
                    if (!(value instanceof Number)) {
                        // 尝试转换
                        try {
                            if (value instanceof String) {
                                newData.add(Double.parseDouble((String) value));
                                converted = true;
                            } else {
                                newData.add(null);
                                converted = true;
                            }
                        } catch (NumberFormatException e) {
                            newData.add(null);
                            converted = true;
                        }
                    } else {
                        newData.add(value);
                    }
                }
                if (converted) {
                    dataset.put("data", newData);
                    changes.add("datasets[" + idx + "]包含非数值数据，已尝试转换");
                }
            }
        }

        boolean success = !changes.isEmpty();
        return new RepairResult(success, repaired, "local", changes);
    }

    private List<Map<String, Object>> tryConstructDatasets(Map<String, Object> data) {
        // 如果data直接包含数据数组，尝试构造
        if (data.containsKey("values") && data.get("values") instanceof List) {
            Map<String, Object> dataset = new HashMap<>();
            dataset.put("label", "数据");
            dataset.put("data", data.get("values"));
            return new ArrayList<>(List.of(dataset));
        }

        // 如果data包含series字段
        if (data.containsKey("series") && data.get("series") instanceof List) {
            List<?> seriesList = (List<?>) data.get("series");
            List<Map<String, Object>> datasets = new ArrayList<>();
            for (int idx = 0; idx < seriesList.size(); idx++) {
                Object seriesObj = seriesList.get(idx);
                if (seriesObj instanceof Map) {
                    Map<?, ?> series = (Map<?, ?>) seriesObj;
                    Map<String, Object> dataset = new HashMap<>();
                    // 修复：Map.getOrDefault 泛型问题
                    Object name = series.get("name");
                    dataset.put("label", name != null ? name : "系列 " + (idx + 1));
                    
                    Object seriesData = series.get("data");
                    dataset.put("data", seriesData != null ? seriesData : new ArrayList<>());

                    datasets.add(dataset);
                } else if (seriesObj instanceof List) {
                    Map<String, Object> dataset = new HashMap<>();
                    dataset.put("label", "系列 " + (idx + 1));
                    dataset.put("data", seriesObj);
                    datasets.add(dataset);
                }
            }
            if (!datasets.isEmpty()) {
                return datasets;
            }
        }

        return null;
    }

    private RepairResult repairWithApi(Map<String, Object> widgetBlock, ChartValidator.ValidationResult validationResult, List<BiFunction<Map<String, Object>, List<String>, Map<String, Object>>> llmRepairFns) {
        if (llmRepairFns == null || llmRepairFns.isEmpty()) {
            return new RepairResult(false, null, "api", Collections.emptyList());
        }

        for (int idx = 0; idx < llmRepairFns.size(); idx++) {
            try {
                logger.info("尝试使用Engine {}修复图表", idx + 1);
                BiFunction<Map<String, Object>, List<String>, Map<String, Object>> repairFn = llmRepairFns.get(idx);
                Map<String, Object> repaired = repairFn.apply(widgetBlock, validationResult.getErrors());

                if (repaired != null) {
                    // 验证修复结果
                    ChartValidator.ValidationResult repairedValidation = validator.validate(repaired);
                    if (repairedValidation.isValid()) {
                        return new RepairResult(
                                true,
                                repaired,
                                "api",
                                List.of("使用Engine " + (idx + 1) + "修复成功")
                        );
                    }
                }
            } catch (Exception e) {
                logger.error("Engine {}修复失败: {}", idx + 1, e.getMessage());
            }
        }

        return new RepairResult(false, null, "api", Collections.emptyList());
    }
}
