package com.souyu.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextProcessing {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(TextProcessing.class);

    private TextProcessing() {
    }

    /**
     * 移除输出中的推理过程文本。
     * 这个方法现在更加通用，适用于JSON和Markdown。
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String removeReasoningFromOutput(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmedText = text.trim();

        // 查找最后一个表示“正文开始”的标记
        // 这可以是JSON的 '{' 或 '[', 也可以是Markdown的 '#'
        int lastMarker = -1;
        for (int i = trimmedText.length() - 1; i >= 0; i--) {
            char c = trimmedText.charAt(i);
            if (c == '{' || c == '[' || c == '#') {
                lastMarker = i;
            }
        }

        // 如果没有找到任何标记，直接返回原始文本
        if (lastMarker == -1) {
            return trimmedText;
        }

        // 从最后一个标记所在行的行首开始截取
        int startOfLine = trimmedText.lastIndexOf('\n', lastMarker) + 1;
        return trimmedText.substring(startOfLine).trim();
    }


    /**
     * 清理文本中的JSON标签, 例如 ```json 和 ```
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String cleanJsonTags(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        
        String result = text.trim();
        
        // 移除 ```json, ```, 以及可能的变体
        result = result.replaceAll("(?i)```json\\s*", "");
        result = result.replaceAll("```\\s*$", "");
        result = result.replaceAll("```", "");

        return result.trim();
    }

    /**
     * 清理文本中的Markdown标签
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String cleanMarkdownTags(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = text.trim();
        // 移除```markdown 和 ```标签
        result = result.replaceAll("(?i)```markdown\\s*", "");
        result = result.replaceAll("```\\s*$", "");
        result = result.replaceAll("```", "");

        return result.trim();
    }

    /**
     * 提取并清理响应中的JSON内容, 并尝试解析为Java集合。
     *
     * @param text 原始响应文本
     * @return 解析后的 Map<String, Object> 或 List<Object>, 如果失败则返回一个包含错误信息的Map
     */
    public static Object extractCleanResponse(String text) {
        String cleanedText = cleanJsonTags(text);
        cleanedText = removeReasoningFromOutput(cleanedText);

        // 1. 尝试直接解析
        try {
            return parseJson(cleanedText);
        } catch (JsonProcessingException e) {
            // 忽略, 继续尝试
        }

        // 2. 尝试修复不完整的JSON
        String fixedText = fixIncompleteJson(cleanedText);
        if (fixedText != null && !fixedText.isEmpty()) {
            try {
                return parseJson(fixedText);
            } catch (JsonProcessingException e) {
                // 忽略, 继续尝试
            }
        }

        // 3. 尝试用贪婪正则查找最大的JSON块
        Pattern pattern = Pattern.compile("(\\{.*})|(\\[.*])", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(cleanedText);
        if (matcher.find()) {
            try {
                return parseJson(matcher.group());
            } catch (JsonProcessingException e) {
                // 忽略
            }
        }

        // 4. 如果所有方法都失败，返回错误信息
        logger.error("无法解析JSON响应: {}...", cleanedText.substring(0, Math.min(200, cleanedText.length())));
        return Map.of(
                "error", "JSON解析失败",
                "raw_text", cleanedText
        );
    }

    private static Object parseJson(String json) throws JsonProcessingException {
        if (json == null) {
            throw new JsonProcessingException("Input JSON is null") {};
        }
        String trimmedJson = json.trim();
        if (trimmedJson.startsWith("{")) {
            return mapper.readValue(trimmedJson, new TypeReference<Map<String, Object>>() {});
        } else if (trimmedJson.startsWith("[")) {
            return mapper.readValue(trimmedJson, new TypeReference<List<Object>>() {});
        }
        throw new JsonProcessingException("Not a JSON object or array") {};
    }

    public static String fixIncompleteJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String processedText = text.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");

        // 1. 尝试正则提取所有顶层JSON对象/数组
        Pattern pattern = Pattern.compile("(\\{.*?})|(\\[.*?])", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(processedText);

        List<String> items = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                items.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                items.add(matcher.group(2));
            }
        }

        // 2. 如果提取到了多个对象，说明是多个JSON拼接的，直接合并为数组
        if (items.size() > 1) {
            return "[" + String.join(", ", items) + "]";
        }
        
        // 3. 如果提取结果少于2个，可能是单个残缺对象，尝试补全括号
        try {
            parseJson(processedText);
            return processedText; // 已经是有效的
        } catch (JsonProcessingException e) {
            // 不是有效的，继续补全
        }

        // 括号补全逻辑
        long openBraces = processedText.chars().filter(ch -> ch == '{').count();
        long closeBraces = processedText.chars().filter(ch -> ch == '}').count();
        long openBrackets = processedText.chars().filter(ch -> ch == '[').count();
        long closeBrackets = processedText.chars().filter(ch -> ch == ']').count();

        String fixedText = processedText;
        
        if (openBraces > closeBraces) {
            fixedText += String.join("", Collections.nCopies((int)(openBraces - closeBraces), "}"));
        }
        if (closeBraces > openBraces) {
            fixedText = String.join("", Collections.nCopies((int)(closeBraces - openBraces), "{")) + fixedText;
        }
        if (openBrackets > closeBrackets) {
            fixedText += String.join("", Collections.nCopies((int)(openBrackets - closeBrackets), "]"));
        }
        if (closeBrackets > openBrackets) {
            fixedText = String.join("", Collections.nCopies((int)(closeBrackets - openBrackets), "[")) + fixedText;
        }

        try {
            parseJson(fixedText);
            return fixedText; // 补全后有效
        } catch (JsonProcessingException e) {
            // 补全也失败了
        }
        
        // 4. 如果补全也失败了，但正则提取到了1个对象，那就返回这唯一的救命稻草
        if (items.size() == 1) {
            return items.get(0);
        }

        return ""; // 彻底没救了
    }
}
