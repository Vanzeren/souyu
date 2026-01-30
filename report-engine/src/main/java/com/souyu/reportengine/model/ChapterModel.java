package com.souyu.reportengine.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

public class ChapterModel {

    @Data
    @NoArgsConstructor
    public static class ChapterContent {
        private String chapterId;
        private String title;
        private String anchor;
        private int order;
        private String summary;
        private List<Block> blocks;
        private Map<String, Object> xrefs;
        private List<String> widgets;
        private List<Object> footnotes;
        private List<String> errors;
        private Map<String, Object> metadata;
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true
    )
    @JsonSubTypes({
        @JsonSubTypes.Type(value = HeadingBlock.class, name = "heading"),
        @JsonSubTypes.Type(value = ParagraphBlock.class, name = "paragraph"),
        @JsonSubTypes.Type(value = ListBlock.class, name = "list"),
        @JsonSubTypes.Type(value = TableBlock.class, name = "table"),
        @JsonSubTypes.Type(value = BlockquoteBlock.class, name = "blockquote"),
        @JsonSubTypes.Type(value = EngineQuoteBlock.class, name = "engineQuote"),
        @JsonSubTypes.Type(value = HrBlock.class, name = "hr"),
        @JsonSubTypes.Type(value = CodeBlock.class, name = "code"),
        @JsonSubTypes.Type(value = MathBlock.class, name = "math"),
        @JsonSubTypes.Type(value = FigureBlock.class, name = "figure"),
        @JsonSubTypes.Type(value = CalloutBlock.class, name = "callout"),
        @JsonSubTypes.Type(value = KpiGridBlock.class, name = "kpiGrid"),
        @JsonSubTypes.Type(value = WidgetBlock.class, name = "widget"),
        @JsonSubTypes.Type(value = TocBlock.class, name = "toc")
    })
    public static abstract class Block {
        public String type;
    }

    @Data
    public static class HeadingBlock extends Block {
        private int level;
        private String text;
        private String anchor;
        private String numbering;
        private String subtitle;
    }

    @Data
    public static class ParagraphBlock extends Block {
        private List<InlineRun> inlines;
        private String align;
    }

    @Data
    public static class ListBlock extends Block {
        private String listType; // ordered, bullet, task
        // items is List<List<Block>>
        private List<List<Block>> items;
    }

    @Data
    public static class TableBlock extends Block {
        private List<Object> colgroup;
        private List<TableRow> rows;
        private String caption;
        private boolean zebra;
    }

    @Data
    public static class TableRow {
        private List<TableCell> cells;
    }

    @Data
    public static class TableCell {
        private int rowspan;
        private int colspan;
        private String align;
        private List<Block> blocks;
    }

    @Data
    public static class BlockquoteBlock extends Block {
        private List<Block> blocks;
        private String variant;
    }

    @Data
    public static class EngineQuoteBlock extends Block {
        private String engine; // insight, media, query
        private String title;
        private List<Block> blocks;
    }

    @Data
    public static class HrBlock extends Block {
        private String variant;
    }

    @Data
    public static class CodeBlock extends Block {
        private String lang;
        private String content;
        private String caption;
    }

    @Data
    public static class MathBlock extends Block {
        private String latex;
        private boolean displayMode;
    }

    @Data
    public static class FigureBlock extends Block {
        private ImageInfo img;
        private String caption;
        private boolean responsive;
    }

    @Data
    public static class ImageInfo {
        private String src;
        private String alt;
        private Double width;
        private Double height;
        private String srcset;
    }

    @Data
    public static class CalloutBlock extends Block {
        private String tone; // info, warning, success, danger
        private String title;
        private List<Block> blocks;
    }

    @Data
    public static class KpiGridBlock extends Block {
        private List<KpiItem> items;
        private Integer cols;
    }

    @Data
    public static class KpiItem {
        private String label;
        private String value;
        private String unit;
        private String delta;
        private String deltaTone; // up, down, neutral
    }

    @Data
    public static class WidgetBlock extends Block {
        private String widgetId;
        private String widgetType;
        private Map<String, Object> props;
        private Map<String, Object> data;
        private String dataRef;
    }

    @Data
    public static class TocBlock extends Block {
        private Integer depth;
        private boolean autoNumbering;
    }

    @Data
    public static class InlineRun {
        private String text;
        private List<InlineMark> marks;
    }

    @Data
    public static class InlineMark {
        private String type; // bold, italic, etc.
        private Object value;
        private String href;
        private String title;
        private Map<String, Object> style;
    }
}
