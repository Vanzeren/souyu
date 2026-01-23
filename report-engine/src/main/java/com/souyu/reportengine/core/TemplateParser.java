package com.souyu.reportengine.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown模板切片工具。
 * <p>
 * LLM需要“按章调用”，因此必须把Markdown模板解析为结构化章节队列。
 * 这里通过轻量正则和缩进启发式，兼容“# 标题”与
 * “- **1.0 标题** /   - 1.1 子标题”等多种写法。
 */
public class TemplateParser {

    private static final int SECTION_ORDER_STEP = 10;

    // 解析表达式刻意避免使用 `.*`，以保持匹配的确定性，
    // 并规避不可信模板文本中常见的正则DoS风险。
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?<marker>#{1,6})[ \\t]+(?<title>[^\\r\\n]+)"
    );

    private static final Pattern BULLET_PATTERN = Pattern.compile(
            "(?<marker>[-*+])[ \\t]+(?<title>[^\\r\\n]+)"
    );

    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "(?<num>(?:0|[1-9]\\d*)(?:\\.(?:0|[1-9]\\d*))*)((?:[ \\t\\u00A0\\u3000、:：-]+|\\.(?!\\d))+(?<label>[^\\r\\n]*))?"
    );

    /**
     * 将Markdown模板切分成章节列表（按大标题）。
     * <p>
     * 返回的每个TemplateSection都携带slug/order/章节号，
     * 方便后续分章调用与锚点生成。解析时会同时兼容
     * “# 标题”“无符号编号”“列表提纲”等不同写法。
     *
     * @param templateMd 模板Markdown全文。
     * @return 结构化的章节序列。
     */
    public static List<TemplateSection> parseTemplateSections(String templateMd) {
        List<TemplateSection> sections = new ArrayList<>();
        TemplateSection current = null;
        int order = SECTION_ORDER_STEP;
        Set<String> usedSlugs = new HashSet<>();

        String[] lines = templateMd.split("\\r?\\n");

        for (String rawLine : lines) {
            if (rawLine.trim().isEmpty()) {
                continue;
            }

            int indent = rawLine.length() - rawLine.stripLeading().length();
            String stripped = rawLine.trim();

            Map<String, Object> meta = classifyLine(stripped, indent);
            if (meta == null) {
                continue;
            }

            if ((boolean) meta.get("is_section")) {
                String slug = ensureUniqueSlug((String) meta.get("slug"), usedSlugs);
                TemplateSection section = new TemplateSection(
                        (String) meta.get("title"),
                        slug,
                        order,
                        (int) meta.get("depth"),
                        (String) meta.get("raw"),
                        (String) meta.get("number")
                );
                sections.add(section);
                current = section;
                order += SECTION_ORDER_STEP;
                continue;
            }

            // 提纲条目
            if (current != null) {
                current.getOutline().add((String) meta.get("title"));
            }
        }

        for (int idx = 0; idx < sections.size(); idx++) {
            // 为每个章节生成稳定的chapter_id，便于后续引用
            sections.get(idx).setChapterId("S" + (idx + 1));
        }

        return sections;
    }

    private static Map<String, Object> classifyLine(String stripped, int indent) {
        Matcher headingMatch = HEADING_PATTERN.matcher(stripped);
        if (headingMatch.matches()) {
            int level = headingMatch.group("marker").length();
            String payload = stripMarkup(headingMatch.group("title").trim());
            Map<String, String> titleInfo = splitNumber(payload);
            String slug = buildSlug(titleInfo.get("number"), titleInfo.get("title"));
            Map<String, Object> result = new HashMap<>();
            result.put("is_section", level <= 2);
            result.put("depth", level);
            result.put("title", titleInfo.get("display"));
            result.put("raw", payload);
            result.put("number", titleInfo.get("number"));
            result.put("slug", slug);
            return result;
        }

        Matcher bulletMatch = BULLET_PATTERN.matcher(stripped);
        if (bulletMatch.matches()) {
            String payload = stripMarkup(bulletMatch.group("title").trim());
            Map<String, String> titleInfo = splitNumber(payload);
            String slug = buildSlug(titleInfo.get("number"), titleInfo.get("title"));
            boolean isSection = indent <= 1;
            int depth = isSection ? 1 : 2;
            Map<String, Object> result = new HashMap<>();
            result.put("is_section", isSection);
            result.put("depth", depth);
            result.put("title", titleInfo.get("display"));
            result.put("raw", payload);
            result.put("number", titleInfo.get("number"));
            result.put("slug", slug);
            return result;
        }

        // 兼容“1.1 ...”没有前缀符号的行
        Matcher numberMatch = NUMBER_PATTERN.matcher(stripped);
        if (numberMatch.matches() && numberMatch.group("label") != null) {
            String payload = stripped;
            String title = numberMatch.group("label").trim();
            String number = numberMatch.group("num");
            String slug = buildSlug(number, title);
            boolean isSection = indent == 0 && countOccurrences(number, '.') <= 1;
            int depth = isSection ? 1 : 2;
            String display = (title != null && !title.isEmpty()) ? number + " " + title : number;
            Map<String, Object> result = new HashMap<>();
            result.put("is_section", isSection);
            result.put("depth", depth);
            result.put("title", display);
            result.put("raw", payload);
            result.put("number", number);
            result.put("slug", slug);
            return result;
        }

        return null;
    }

    private static String stripMarkup(String text) {
        if ((text.startsWith("**") && text.endsWith("**")) || (text.startsWith("__") && text.endsWith("__"))) {
            if (text.length() > 4) {
                return text.substring(2, text.length() - 2).trim();
            }
        }
        return text;
    }

    private static Map<String, String> splitNumber(String payload) {
        Matcher match = NUMBER_PATTERN.matcher(payload);
        String number = "";
        String label = payload;
        if (match.matches()) {
            number = match.group("num");
            label = match.group("label");
            if (label == null) label = payload; // Fallback if label group is not captured but match succeeds (unlikely with current regex but safe)
        }
        
        label = (label != null) ? label.trim() : "";
        String display = (!number.isEmpty()) ? number + " " + label : label;
        if (display.isEmpty()) display = payload;
        
        String titleCore = (!label.isEmpty()) ? label : payload;

        Map<String, String> result = new HashMap<>();
        result.put("number", number);
        result.put("title", titleCore);
        result.put("display", display.trim());
        return result;
    }

    private static String buildSlug(String number, String title) {
        String token;
        if (number != null && !number.isEmpty()) {
            token = number.replace(".", "-");
        } else {
            token = slugifyText(title);
        }
        if (token == null || token.isEmpty()) {
            token = "section";
        }
        return "section-" + token;
    }

    private static String slugifyText(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD);
        normalized = normalized.replace("·", "-").replace(" ", "-");
        normalized = normalized.replaceAll("[^0-9a-zA-Z\\u4e00-\\u9fff-]+", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        return normalized.replaceAll("^-|-$", "").toLowerCase();
    }

    private static String ensureUniqueSlug(String slug, Set<String> used) {
        if (!used.contains(slug)) {
            used.add(slug);
            return slug;
        }
        String base = slug;
        int idx = 2;
        while (used.contains(slug)) {
            slug = base + "-" + idx;
            idx++;
        }
        used.add(slug);
        return slug;
    }

    private static int countOccurrences(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }
}
