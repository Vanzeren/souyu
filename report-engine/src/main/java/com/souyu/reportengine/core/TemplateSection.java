package com.souyu.reportengine.core;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板章节实体。
 * <p>
 * 记录标题、slug、序号、层级、原始标题、章节编号与提纲，
 * 方便后续节点在提示词中引用并保持锚点一致。
 */
@Data
@NoArgsConstructor
public class TemplateSection {

    private String title;
    private String slug;
    private int order;
    private int depth;
    private String rawTitle;
    private String number = "";
    private String chapterId = "";
    private List<String> outline = new ArrayList<>();

    public TemplateSection(String title, String slug, int order, int depth, String rawTitle, String number) {
        this.title = title;
        this.slug = slug;
        this.order = order;
        this.depth = depth;
        this.rawTitle = rawTitle;
        this.number = number;
    }

    /**
     * 将章节实体序列化为字典。
     * <p>
     * 该结构广泛用于提示词上下文以及 layout/word budget 节点的输入。
     */
    public Map<String, Object> toDict() {
        Map<String, Object> dict = new HashMap<>();
        dict.put("title", this.title);
        dict.put("slug", this.slug);
        dict.put("order", this.order);
        dict.put("depth", this.depth);
        dict.put("number", this.number);
        dict.put("chapterId", this.chapterId);
        dict.put("outline", this.outline);
        return dict;
    }
}
