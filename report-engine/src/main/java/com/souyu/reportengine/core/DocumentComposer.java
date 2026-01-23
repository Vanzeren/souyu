package com.souyu.reportengine.core;

import com.souyu.reportengine.schema.Schema;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 章节装订器：负责把多个章节JSON合并为整本IR。
 * <p>
 * DocumentComposer 会注入缺失锚点、统一顺序，并补齐 IR 级元数据。
 */
@Component
public class DocumentComposer {

    private final Set<String> seenAnchors = new HashSet<>();

    /**
     * 把所有章节按order排序并注入唯一锚点，形成整本IR。
     * <p>
     * 同时合并 metadata/themeTokens/assets，供渲染器直接消费。
     *
     * @param reportId 本次报告ID。
     * @param metadata 全局元信息（标题、主题、toc等）。
     * @param chapters 章节payload列表。
     * @return 满足渲染器需求的Document IR。
     */
    public Map<String, Object> buildDocument(String reportId, Map<String, Object> metadata, List<Map<String, Object>> chapters) {
        // 构建从chapterId到toc anchor的映射
        Map<String, String> tocAnchorMap = buildTocAnchorMap(metadata);

        // 按order排序
        List<Map<String, Object>> ordered = new ArrayList<>(chapters);
        ordered.sort(Comparator.comparingInt(c -> (int) c.getOrDefault("order", 0)));

        // 重置已见锚点集合，确保每次构建都是独立的
        seenAnchors.clear();

        for (int i = 0; i < ordered.size(); i++) {
            Map<String, Object> chapter = ordered.get(i);
            int idx = i + 1;

            chapter.putIfAbsent("chapterId", "S" + idx);

            // 优先级：1. 目录配置的anchor 2. 章节自带的anchor 3. 默认anchor
            String chapterId = (String) chapter.get("chapterId");
            String anchor = tocAnchorMap.get(chapterId);
            if (anchor == null) {
                anchor = (String) chapter.get("anchor");
            }
            if (anchor == null) {
                anchor = "section-" + idx;
            }

            chapter.put("anchor", ensureUniqueAnchor(anchor));
            chapter.putIfAbsent("order", idx * 10);

            if (Boolean.TRUE.equals(chapter.get("errorPlaceholder"))) {
                ensureHeadingBlock(chapter);
            }
        }

        Map<String, Object> document = new HashMap<>();
        document.put("version", Schema.IR_VERSION);
        document.put("reportId", reportId);

        Map<String, Object> finalMetadata = new HashMap<>(metadata);
        if (!finalMetadata.containsKey("generatedAt")) {
            finalMetadata.put("generatedAt", LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME) + "Z");
        }
        document.put("metadata", finalMetadata);
        document.put("themeTokens", metadata.getOrDefault("themeTokens", new HashMap<>()));
        document.put("chapters", ordered);
        document.put("assets", metadata.getOrDefault("assets", new HashMap<>()));

        return document;
    }

    private String ensureUniqueAnchor(String anchor) {
        String uniqueAnchor = anchor;
        int counter = 2;
        while (seenAnchors.contains(uniqueAnchor)) {
            uniqueAnchor = anchor + "-" + counter;
            counter++;
        }
        seenAnchors.add(uniqueAnchor);
        return uniqueAnchor;
    }

    private Map<String, String> buildTocAnchorMap(Map<String, Object> metadata) {
        Map<String, Object> tocConfig = (Map<String, Object>) metadata.getOrDefault("toc", new HashMap<>());
        List<Map<String, Object>> customEntries = (List<Map<String, Object>>) tocConfig.getOrDefault("customEntries", new ArrayList<>());
        Map<String, String> anchorMap = new HashMap<>();

        for (Map<String, Object> entry : customEntries) {
            String chapterId = (String) entry.get("chapterId");
            String anchor = (String) entry.get("anchor");
            if (chapterId != null && anchor != null) {
                anchorMap.put(chapterId, anchor);
            }
        }
        return anchorMap;
    }

    private void ensureHeadingBlock(Map<String, Object> chapter) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) chapter.get("blocks");
        if (blocks == null) {
            blocks = new ArrayList<>();
            chapter.put("blocks", blocks);
        }

        for (Map<String, Object> block : blocks) {
            if ("heading".equals(block.get("type"))) {
                return;
            }
        }

        Map<String, Object> heading = new HashMap<>();
        heading.put("type", "heading");
        heading.put("level", 2);
        heading.put("text", chapter.getOrDefault("title", "占位章节"));
        heading.put("anchor", chapter.get("anchor"));

        blocks.add(0, heading);
    }
}
