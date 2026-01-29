package com.souyu.reportengine.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的JSON解析和修复工具。
 * <p>
 * 提供鲁棒的JSON解析能力，支持：
 * 1. 自动清理markdown代码块标记和思考内容
 * 2. 本地语法修复（括号平衡、逗号补全、控制字符转义等）
 * 3. LLM辅助修复（可选）
 * 4. 详细的错误日志和调试信息
 */
@Component
public class JsonParser {

    private static final Logger logger = LoggerFactory.getLogger(JsonParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 常见的LLM思考内容模式
    private static final List<Pattern> THINKING_PATTERNS = List.of(
            Pattern.compile("^\\s*<thinking>.*?</thinking>\\s*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*<thought>.*?</thought>\\s*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*让我想想.*?(?=\\{|\\[|$)", Pattern.DOTALL),
            Pattern.compile("^\\s*首先.*?(?=\\{|\\[|$)", Pattern.DOTALL),
            Pattern.compile("^\\s*分析.*?(?=\\{|\\[|$)", Pattern.DOTALL),
            Pattern.compile("^\\s*根据.*?(?=\\{|\\[|$)", Pattern.DOTALL)
    );

    // 冒号等号模式（LLM常见错误）
    private static final Pattern COLON_EQUALS_PATTERN = Pattern.compile("(\":\\s*)=");

    private final BiFunction<String, String, String> llmRepairFn;
    private final boolean enableLlmRepair;
    private final int maxRepairAttempts;

    public JsonParser() {
        this(null, false, 3);
    }

    public JsonParser(BiFunction<String, String, String> llmRepairFn, boolean enableLlmRepair, int maxRepairAttempts) {
        this.llmRepairFn = llmRepairFn;
        this.enableLlmRepair = enableLlmRepair;
        this.maxRepairAttempts = maxRepairAttempts;
    }

    public static class JsonParseError extends RuntimeException {
        private final String rawText;

        public JsonParseError(String message, String rawText) {
            super(message);
            this.rawText = rawText;
        }

        public JsonParseError(String message, String rawText, Throwable cause) {
            super(message, cause);
            this.rawText = rawText;
        }

        public String getRawText() {
            return rawText;
        }
    }

    public Map<String, Object> parse(String rawText, String contextName, List<String> expectedKeys, String extractWrapperKey) {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new JsonParseError(contextName + "返回空内容", rawText);
        }

        String originalText = rawText;
        List<String> candidates = buildCandidatePayloads(rawText, contextName);

        Exception lastError = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                Map<String, Object> data = objectMapper.readValue(candidate, Map.class);
                logger.debug("{} JSON解析成功（候选{}/{}）", contextName, i + 1, candidates.size());
                return extractAndValidate(data, expectedKeys, extractWrapperKey, contextName);
            } catch (Exception exc) {
                lastError = exc;
                logger.debug("{} 候选{}解析失败: {}", contextName, i + 1, exc.getMessage());
            }
        }

        String cleaned = candidates.isEmpty() ? originalText : candidates.get(0);

        // Java版暂未集成 json_repair 库，跳过该步骤

        if (enableLlmRepair && llmRepairFn != null) {
            String llmRepaired = attemptLlmRepair(cleaned, lastError != null ? lastError.getMessage() : "Unknown error", contextName);
            if (llmRepaired != null) {
                try {
                    Map<String, Object> data = objectMapper.readValue(llmRepaired, Map.class);
                    logger.info("{} JSON通过LLM修复成功", contextName);
                    return extractAndValidate(data, expectedKeys, extractWrapperKey, contextName);
                } catch (Exception exc) {
                    lastError = exc;
                    logger.warn("{} LLM修复后仍无法解析: {}", contextName, exc.getMessage());
                }
            }
        }

        String errorMsg = contextName + " JSON解析失败: " + (lastError != null ? lastError.getMessage() : "Unknown error");
        logger.error(errorMsg);
        logger.debug("原始文本前500字符: {}", originalText.substring(0, Math.min(originalText.length(), 500)));
        throw new JsonParseError(errorMsg, originalText, lastError);
    }

    private List<String> buildCandidatePayloads(String rawText, String contextName) {
        String cleaned = cleanResponse(rawText);
        List<String> candidates = new ArrayList<>();
        candidates.add(cleaned);

        String localRepaired = applyLocalRepairs(cleaned);
        if (!localRepaired.equals(cleaned)) {
            candidates.add(localRepaired);
        }

        String flattened = flattenNestedArrays(localRepaired);
        if (!candidates.contains(flattened)) {
            candidates.add(flattened);
        }

        return candidates;
    }

    private String cleanResponse(String raw) {
        String cleaned = raw.trim();

        for (Pattern pattern : THINKING_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }

        Pattern fencedPattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = fencedPattern.matcher(cleaned);
        if (matcher.find()) {
            cleaned = matcher.group(1).trim();
        } else {
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }

        return extractFirstJsonStructure(cleaned);
    }

    private String extractFirstJsonStructure(String text) {
        int startBrace = text.indexOf('{');
        int startBracket = text.indexOf('[');

        if (startBrace == -1 && startBracket == -1) {
            return text;
        }

        int start;
        char opener;
        if (startBrace == -1) {
            start = startBracket;
            opener = '[';
        } else if (startBracket == -1) {
            start = startBrace;
            opener = '{';
        } else {
            start = Math.min(startBrace, startBracket);
            opener = text.charAt(start);
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                escaped = true;
                continue;
            }

            if (ch == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        return start < text.length() ? text.substring(start) : text;
    }

    private String applyLocalRepairs(String text) {
        String repaired = text;
        boolean mutated = false;

        Matcher matcher = COLON_EQUALS_PATTERN.matcher(repaired);
        if (matcher.find()) {
            repaired = matcher.replaceAll("$1");
            logger.warn("检测到\":=\"字符，已自动移除多余的'='号");
            mutated = true;
        }

        Map.Entry<String, Boolean> escapedResult = escapeControlCharacters(repaired);
        if (escapedResult.getValue()) {
            repaired = escapedResult.getKey();
            logger.warn("检测到未转义的控制字符，已自动转换为转义序列");
            mutated = true;
        }

        Map.Entry<String, Boolean> commasResult = fixMissingCommas(repaired);
        if (commasResult.getValue()) {
            repaired = commasResult.getKey();
            logger.warn("检测到对象/数组之间缺少逗号，已自动补齐");
            mutated = true;
        }

        Map.Entry<String, Boolean> collapsedResult = collapseRedundantBrackets(repaired);
        if (collapsedResult.getValue()) {
            repaired = collapsedResult.getKey();
            logger.warn("检测到连续的方括号嵌套，已尝试折叠为二维结构");
            mutated = true;
        }

        Map.Entry<String, Boolean> balancedResult = balanceBrackets(repaired);
        if (balancedResult.getValue()) {
            repaired = balancedResult.getKey();
            logger.warn("检测到括号不平衡，已自动补齐/剔除异常括号");
            mutated = true;
        }

        Map.Entry<String, Boolean> trailingResult = removeTrailingCommas(repaired);
        if (trailingResult.getValue()) {
            repaired = trailingResult.getKey();
            logger.warn("检测到尾随逗号，已自动移除");
            mutated = true;
        }

        Map.Entry<String, Boolean> singleQuoteResult = fixSingleQuotes(repaired);
        if (singleQuoteResult.getValue()) {
            repaired = singleQuoteResult.getKey();
            logger.warn("检测到单引号，已自动替换为双引号");
            mutated = true;
        }
        
        // 新增：修复数组误用为对象的情况
        Map.Entry<String, Boolean> arrayAsObjectResult = fixArrayAsObject(repaired);
        if (arrayAsObjectResult.getValue()) {
            repaired = arrayAsObjectResult.getKey();
            logger.warn("检测到数组误用为对象（[]包含键值对），已自动转换为对象{}");
            mutated = true;
        }

        return mutated ? repaired : text;
    }
    
    private Map.Entry<String, Boolean> fixArrayAsObject(String text) {
        if (text == null) return Map.entry(text, false);
        
        String trimmed = text.trim();
        // 检查是否以 [ 开头，以 ] 结尾
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            // 检查内部是否直接包含 "key": 结构
            // 这是一个简单的启发式检查：查找第一个 " 后的 :
            int firstQuote = trimmed.indexOf('"');
            if (firstQuote != -1) {
                int afterQuote = trimmed.indexOf('"', firstQuote + 1);
                if (afterQuote != -1) {
                    int colon = trimmed.indexOf(':', afterQuote);
                    // 确保中间没有逗号或其他干扰，且冒号确实存在
                    if (colon != -1) {
                        String between = trimmed.substring(afterQuote + 1, colon);
                        if (between.trim().isEmpty()) {
                            // 确实是 [ "key" : ... 模式
                            // 将首尾的 [] 替换为 {}
                            String repaired = "{" + trimmed.substring(1, trimmed.length() - 1) + "}";
                            return Map.entry(repaired, true);
                        }
                    }
                }
            }
        }
        return Map.entry(text, false);
    }

    private Map.Entry<String, Boolean> fixSingleQuotes(String text) {
        if (text == null) return Map.entry(text, false);

        // 简单替换：将所有单引号替换为双引号，但这可能会破坏内容中的单引号
        // 更稳健的方法是只替换作为JSON键或值边界的单引号
        // 这里使用一个简单的启发式方法：如果看起来像JSON键或值被单引号包围，则替换
        
        // 替换键: 'key': -> "key":
        String repaired = text.replaceAll("'([^']+)'\\s*:", "\"$1\":");
        
        // 替换值: : 'value' -> : "value" (简单处理，不处理嵌套引号)
        repaired = repaired.replaceAll(":\\s*'([^']*)'", ": \"$1\"");
        
        // 替换数组中的字符串: ['a', 'b'] -> ["a", "b"]
        repaired = repaired.replaceAll("\\[\\s*'([^']*)'", "[\"$1\"");
        repaired = repaired.replaceAll(",\\s*'([^']*)'", ", \"$1\"");
        repaired = repaired.replaceAll("'([^']*)'\\s*\\]", "\"$1\"]");

        return Map.entry(repaired, !repaired.equals(text));
    }

    private Map.Entry<String, Boolean> escapeControlCharacters(String text) {
        if (text == null) return Map.entry(text, false);

        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        boolean mutated = false;
        Map<Character, String> controlMap = Map.of('\n', "\\n", '\r', "\\r", '\t', "\\t");

        for (char ch : text.toCharArray()) {
            if (escaped) {
                result.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                result.append(ch);
                escaped = true;
                continue;
            }

            if (ch == '"') {
                result.append(ch);
                inString = !inString;
                continue;
            }

            if (inString && controlMap.containsKey(ch)) {
                result.append(controlMap.get(ch));
                mutated = true;
                continue;
            }

            if (inString && ch < 0x20) {
                result.append(String.format("\\u%04x", (int) ch));
                mutated = true;
                continue;
            }

            result.append(ch);
        }

        return Map.entry(result.toString(), mutated);
    }

    private Map.Entry<String, Boolean> fixMissingCommas(String text) {
        if (text == null) return Map.entry(text, false);

        StringBuilder chars = new StringBuilder();
        boolean mutated = false;
        boolean inString = false;
        boolean escaped = false;
        int length = text.length();

        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            chars.append(ch);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                escaped = true;
                continue;
            }

            if (ch == '"') {
                if (inString) {
                    int j = i + 1;
                    while (j < length && Character.isWhitespace(text.charAt(j))) {
                        j++;
                    }
                    if (j < length) {
                        char nextCh = text.charAt(j);
                        if (nextCh == '"' || nextCh == '[' || nextCh == '{' || Character.isDigit(nextCh)) {
                            boolean hasOpener = false;
                            for (int k = chars.length() - 1; k >= 0; k--) {
                                char c = chars.charAt(k);
                                if (c == '{' || c == '[') {
                                    hasOpener = true;
                                    break;
                                } else if (c == ']' || c == '}') {
                                    break;
                                }
                            }
                            if (hasOpener) {
                                chars.append(',');
                                mutated = true;
                            }
                        }
                    }
                }
                inString = !inString;
                continue;
            }

            if (!inString && (ch == '}' || ch == ']')) {
                int j = i + 1;
                while (j < length && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j < length) {
                    char nextCh = text.charAt(j);
                    if (nextCh == '{' || nextCh == '[' || nextCh == '"' || Character.isDigit(nextCh)) {
                        chars.append(',');
                        mutated = true;
                    }
                }
            }
        }

        return Map.entry(chars.toString(), mutated);
    }

    private Map.Entry<String, Boolean> collapseRedundantBrackets(String text) {
        if (text == null) return Map.entry(text, false);

        boolean mutated = false;
        String repaired = text;

        // 简化正则替换，Java正则与Python略有不同
        // 典型错误: "]]], [[{...}" -> "]], [{...}"
        Pattern p1 = Pattern.compile("\\]\\s*\\]\\s*\\]\\s*,\\s*\\[\\s*\\[");
        Matcher m1 = p1.matcher(repaired);
        if (m1.find()) {
            repaired = m1.replaceAll("]],[");
            mutated = true;
        }

        // 极端情况: 连续三层开头 "[[[" -> "[["
        Pattern p2 = Pattern.compile("\\[\\s*\\[\\s*\\[");
        Matcher m2 = p2.matcher(repaired);
        if (m2.find()) {
            repaired = m2.replaceAll("[[");
            mutated = true;
        }

        // 极端情况: 结尾 "]]]" -> "]]"
        Pattern p3 = Pattern.compile("\\]\\s*\\]\\s*\\]");
        Matcher m3 = p3.matcher(repaired);
        if (m3.find()) {
            repaired = m3.replaceAll("]]");
            mutated = true;
        }

        return Map.entry(repaired, mutated);
    }

    private String flattenNestedArrays(String text) {
        if (text == null) return text;
        String repaired = text.replaceAll("\\]\\s*\\]\\s*\\]", "]]");
        repaired = repaired.replaceAll("\\[\\s*\\[\\s*\\[", "[[");
        return repaired;
    }

    private Map.Entry<String, Boolean> balanceBrackets(String text) {
        if (text == null) return Map.entry(text, false);

        StringBuilder result = new StringBuilder();
        Deque<Character> stack = new ArrayDeque<>();
        boolean mutated = false;
        boolean inString = false;
        boolean escaped = false;

        Map<Character, Character> openerMap = Map.of('{', '}', '[', ']');

        for (char ch : text.toCharArray()) {
            if (escaped) {
                result.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                result.append(ch);
                escaped = true;
                continue;
            }

            if (ch == '"') {
                result.append(ch);
                inString = !inString;
                continue;
            }

            if (inString) {
                result.append(ch);
                continue;
            }

            if (ch == '{' || ch == '[') {
                stack.push(ch);
                result.append(ch);
                continue;
            }

            if (ch == '}' || ch == ']') {
                if (!stack.isEmpty() && ((ch == '}' && stack.peek() == '{') || (ch == ']' && stack.peek() == '['))) {
                    stack.pop();
                    result.append(ch);
                } else {
                    mutated = true;
                }
                continue;
            }

            result.append(ch);
        }

        while (!stack.isEmpty()) {
            char opener = stack.pop();
            result.append(openerMap.get(opener));
            mutated = true;
        }

        return Map.entry(result.toString(), mutated);
    }

    private Map.Entry<String, Boolean> removeTrailingCommas(String text) {
        if (text == null) return Map.entry(text, false);

        String newText = text.replaceAll(",(\\s*[}\\]])", "$1");
        return Map.entry(newText, !newText.equals(text));
    }

    private String attemptLlmRepair(String text, String errorMsg, String contextName) {
        if (llmRepairFn == null) return null;

        try {
            logger.info("{} 尝试使用LLM修复JSON", contextName);
            String repaired = llmRepairFn.apply(text, errorMsg);
            if (repaired != null && !repaired.equals(text)) {
                return repaired;
            }
        } catch (Exception exc) {
            logger.warn("{} LLM修复失败: {}", contextName, exc.getMessage());
        }
        return null;
    }

    private Map<String, Object> extractAndValidate(Map<String, Object> data, List<String> expectedKeys, String extractWrapperKey, String contextName) {
        Map<String, Object> result = data;

        if (extractWrapperKey != null && result.containsKey(extractWrapperKey)) {
            Object wrapped = result.get(extractWrapperKey);
            if (wrapped instanceof Map) {
                result = (Map<String, Object>) wrapped;
            } else {
                logger.warn("{} 未找到包裹键'{}'，使用原始数据", contextName, extractWrapperKey);
            }
        }

        if (expectedKeys != null) {
            List<String> missingKeys = new ArrayList<>();
            for (String key : expectedKeys) {
                if (!result.containsKey(key)) {
                    missingKeys.add(key);
                }
            }

            if (!missingKeys.isEmpty()) {
                logger.warn("{} 缺少预期的键: {}", contextName, String.join(", ", missingKeys));
                result = tryRecoverMissingKeys(result, missingKeys, contextName);
            }
        }

        return result;
    }

    private Map<String, Object> tryRecoverMissingKeys(Map<String, Object> data, List<String> missingKeys, String contextName) {
        Map<String, List<String>> keyAliases = Map.of(
                "template_name", List.of("templateName", "name", "template"),
                "selection_reason", List.of("selectionReason", "reason", "explanation"),
                "title", List.of("reportTitle", "documentTitle"),
                "chapters", List.of("chapterList", "chapterPlan", "sections"),
                "totalWords", List.of("total_words", "wordCount", "totalWordCount")
        );

        for (String missingKey : missingKeys) {
            if (keyAliases.containsKey(missingKey)) {
                for (String alias : keyAliases.get(missingKey)) {
                    if (data.containsKey(alias)) {
                        logger.info("{} 找到键'{}'的别名'{}'，自动映射", contextName, missingKey, alias);
                        data.put(missingKey, data.get(alias));
                        break;
                    }
                }
            }
        }
        return data;
    }
}
