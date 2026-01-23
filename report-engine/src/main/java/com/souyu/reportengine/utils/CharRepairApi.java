package com.souyu.reportengine.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.client.TimeContextChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 图表API修复模块。
 * <p>
 * 提供调用 LLM API 来修复图表数据的功能。
 */
@Component
public class CharRepairApi {

    private static final Logger logger = LoggerFactory.getLogger(CharRepairApi.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 图表修复提示词
    private static final String CHART_REPAIR_SYSTEM_PROMPT = """
            你是一个专业的图表数据修复助手。你的任务是修复Chart.js图表数据中的格式错误，确保图表能够正常渲染。

            **Chart.js标准数据格式：**

            1. 标准图表（line, bar, pie, doughnut, radar, polarArea）：
            ```json
            {
              "type": "widget",
              "widgetType": "chart.js/bar",
              "widgetId": "chart-001",
              "props": {
                "type": "bar",
                "title": "图表标题",
                "options": {
                  "responsive": true,
                  "plugins": {
                    "legend": {
                      "display": true
                    }
                  }
                }
              },
              "data": {
                "labels": ["A", "B", "C"],
                "datasets": [
                  {
                    "label": "系列1",
                    "data": [10, 20, 30]
                  }
                ]
              }
            }
            ```

            2. 特殊图表（scatter, bubble）：
            ```json
            {
              "data": {
                "datasets": [
                  {
                    "label": "系列1",
                    "data": [
                      {"x": 10, "y": 20},
                      {"x": 15, "y": 25}
                    ]
                  }
                ]
              }
            }
            ```

            **修复原则：**
            1. **宁愿不改，也不要改错** - 如果不确定如何修复，保持原始数据
            2. **最小改动** - 只修复明确的错误，不要过度修改
            3. **保持数据完整性** - 不要丢失原始数据
            4. **验证修复结果** - 确保修复后符合Chart.js格式

            **常见错误及修复方法：**
            1. 缺少labels字段 → 根据数据生成默认labels
            2. datasets不是数组 → 转换为数组格式
            3. 数据长度不匹配 → 截断或补null
            4. 非数值数据 → 尝试转换或设为null
            5. 缺少必需字段 → 添加默认值

            请根据错误信息修复图表数据，并返回修复后的完整widget block（JSON格式）。
            """;

    @Autowired
    private TimeContextChatClient llmClient;

    private String buildChartRepairPrompt(Map<String, Object> widgetBlock, List<String> validationErrors) {
        String blockJson;
        try {
            blockJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(widgetBlock);
        } catch (JsonProcessingException e) {
            blockJson = widgetBlock.toString();
        }
        
        StringBuilder errorsText = new StringBuilder();
        for (String error : validationErrors) {
            errorsText.append("- ").append(error).append("\n");
        }

        return String.format("""
                请修复以下图表数据中的错误：

                **原始数据：**
                ```json
                %s
                ```

                **检测到的错误：**
                %s

                **要求：**
                1. 返回修复后的完整widget block（JSON格式）
                2. 只修复明确的错误，保持其他数据不变
                3. 确保修复后的数据符合Chart.js格式要求
                4. 如果无法确定如何修复，保持原始数据

                **重要的输出格式要求：**
                1. 只返回纯JSON对象，不要添加任何说明文字
                2. 不要使用```json```标记包裹
                3. 确保JSON语法完全正确
                4. 所有字符串使用双引号
                """, blockJson, errorsText.toString());
    }

    /**
     * 创建LLM修复函数列表。
     *
     * @return 修复函数列表
     */
    public List<BiFunction<Map<String, Object>, List<String>, Map<String, Object>>> createLlmRepairFunctions() {
        List<BiFunction<Map<String, Object>, List<String>, Map<String, Object>>> repairFunctions = new ArrayList<>();

        // 添加通用修复函数
        repairFunctions.add(this::repairWithLlm);
        
        // 如果需要重试机制，可以多次添加同一个函数，或者在调用端处理重试
        // 这里只添加一次

        return repairFunctions;
    }

    private Map<String, Object> repairWithLlm(Map<String, Object> widgetBlock, List<String> errors) {
        try {
            String promptText = buildChartRepairPrompt(widgetBlock, errors);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(CHART_REPAIR_SYSTEM_PROMPT),
                    new UserMessage(promptText)
            ));
            
            String response = llmClient.streamAndCollect(prompt).block();

            if (response == null || response.isEmpty()) {
                return null;
            }

            return parseJson(response);

        } catch (Exception e) {
            logger.error("LLM图表修复失败: {}", e.getMessage());
            return null;
        }
    }

    // 辅助方法：解析 JSON
    private Map<String, Object> parseJson(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            return objectMapper.readValue(cleaned.trim(), Map.class);
        } catch (Exception e) {
            logger.error("JSON解析失败: {}", e.getMessage());
            return null;
        }
    }
}
