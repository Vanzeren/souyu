package com.souyu.reportengine.angent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.reportengine.ReportStateManager.reportStateManager;
import com.souyu.reportengine.config.ReportEngineConfig;
import com.souyu.reportengine.core.ChapterStorage;
import com.souyu.reportengine.core.DocumentComposer;
import com.souyu.reportengine.core.TemplateParser;
import com.souyu.reportengine.core.TemplateSection;
import com.souyu.reportengine.nodes.ChapterGenerationNode;
import com.souyu.reportengine.nodes.DocumentLayoutNode;
import com.souyu.reportengine.nodes.TemplateSelectionNode;
import com.souyu.reportengine.nodes.WordBudgetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Report Agent主类。
 * <p>
 * 该模块串联模板选择、布局设计、章节生成、IR装订与HTML渲染等
 * 所有子流程，是Report Engine的总调度中心。核心职责包括：
 * 1. 管理输入数据与状态，协调三个分析引擎、论坛日志与模板；
 * 2. 按节点顺序驱动模板选择→布局生成→篇幅规划→章节写作→装订渲染；
 * 3. 负责错误兜底、流式事件分发、落盘清单与最终成果保存。
 */
@Component
public class ReportAgent {

    private static final Logger logger = LoggerFactory.getLogger(ReportAgent.class);
    private static final int CONTENT_SPARSE_MIN_ATTEMPTS = 3;

    private final ReportEngineConfig config;
    private final ChapterStorage chapterStorage;
    private final DocumentComposer documentComposer;
    private final reportStateManager stateManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Nodes
    private final TemplateSelectionNode templateSelectionNode;
    private final DocumentLayoutNode documentLayoutNode;
    private final WordBudgetNode wordBudgetNode;
    private final ChapterGenerationNode chapterGenerationNode;

    @Autowired
    public ReportAgent(
            ReportEngineConfig config,
            ChapterStorage chapterStorage,
            DocumentComposer documentComposer,
            reportStateManager stateManager,
            TemplateSelectionNode templateSelectionNode,
            DocumentLayoutNode documentLayoutNode,
            WordBudgetNode wordBudgetNode,
            ChapterGenerationNode chapterGenerationNode
    ) {
        this.config = config;
        this.chapterStorage = chapterStorage;
        this.documentComposer = documentComposer;
        this.stateManager = stateManager;
        this.templateSelectionNode = templateSelectionNode;
        this.documentLayoutNode = documentLayoutNode;
        this.wordBudgetNode = wordBudgetNode;
        this.chapterGenerationNode = chapterGenerationNode;
    }

    /**
     * 生成综合报告（章节JSON → IR → HTML）。
     *
     * @param query          最终要生成的报告主题或提问语句。
     * @param reports        来自 Query/Media/Insight 等分析引擎的原始输出。
     * @param forumLogs      论坛/协同记录，供LLM理解多人讨论上下文。
     * @param customTemplate 用户指定的Markdown模板，如为空则交由模板节点自动挑选。
     * @param streamHandler  可选的流式事件回调，接收阶段标签与payload，用于UI实时展示。
     * @return 包含 `html_content` 以及HTML/IR/状态文件路径的字典。
     */
    public Map<String, Object> generateReport(
            String query,
            List<Object> reports,
            String forumLogs,
            String customTemplate,
            BiConsumer<String, Map<String, Object>> streamHandler
    ) {
        String reportId = stateManager.initReportState(query);
        stateManager.updateState(reportId, state -> state.markProcessing());

        Map<String, String> normalizedReports = normalizeReports(reports);

        BiConsumer<String, Map<String, Object>> emit = (eventType, payload) -> {
            if (streamHandler != null) {
                try {
                    streamHandler.accept(eventType, payload);
                } catch (Exception e) {
                    logger.warn("流式事件回调失败: {}", e.getMessage());
                }
            }
        };

        logger.info("开始生成报告 {}: {}", reportId, query);
        emit.accept("stage", Map.of("stage", "agent_start", "report_id", reportId, "query", query));

        try {
            // 1. 模板选择
            Map<String, Object> templateResult = selectTemplate(query, reports, forumLogs, customTemplate);
            stateManager.updateState(reportId, state -> state.getMetadata().setTemplateUsed((String) templateResult.get("template_name")));
            
            emit.accept("stage", Map.of(
                    "stage", "template_selected",
                    "template", templateResult.getOrDefault("template_name", "unknown"),
                    "reason", templateResult.getOrDefault("selection_reason", "unknown")
            ));
            emit.accept("progress", Map.of("progress", 10, "message", "模板选择完成"));

            // 2. 模板切片
            List<TemplateSection> sections = sliceTemplate((String) templateResult.get("template_content"));
            if (sections.isEmpty()) {
                throw new RuntimeException("模板无法解析出章节，请检查模板内容。");
            }
            emit.accept("stage", Map.of("stage", "template_sliced", "section_count", sections.size()));

            String templateText = (String) templateResult.get("template_content");
            Map<String, Object> templateOverview = buildTemplateOverview(templateText, sections);

            // 3. 文档布局设计
            Map<String, Object> layoutDesign = documentLayoutNode.run(new DocumentLayoutNode.Input(
                    sections,
                    templateText,
                    normalizedReports,
                    forumLogs,
                    query,
                    templateOverview
            ));
            emit.accept("stage", Map.of(
                    "stage", "layout_designed",
                    "title", layoutDesign.get("title"),
                    "toc", layoutDesign.getOrDefault("tocTitle", "")
            ));
            emit.accept("progress", Map.of("progress", 15, "message", "文档标题/目录设计完成"));

            // 4. 章节篇幅规划
            Map<String, Object> wordPlan = wordBudgetNode.run(new WordBudgetNode.Input(
                    sections,
                    layoutDesign,
                    normalizedReports,
                    forumLogs,
                    query,
                    templateOverview
            ));
            emit.accept("stage", Map.of(
                    "stage", "word_plan_ready",
                    "chapter_targets", ((List<?>) wordPlan.get("chapters")).size()
            ));
            emit.accept("progress", Map.of("progress", 20, "message", "章节字数规划已生成"));

            // 5. 准备生成上下文
            Map<String, Object> chapterPlanMap = new HashMap<>();
            List<Map<String, Object>> chaptersPlan = (List<Map<String, Object>>) wordPlan.get("chapters");
            for (Map<String, Object> entry : chaptersPlan) {
                String chapterId = (String) entry.get("chapterId");
                if (chapterId != null) {
                    chapterPlanMap.put(chapterId, entry);
                }
            }

            Map<String, Object> context = new HashMap<>();
            context.put("query", query);
            context.put("template_name", templateResult.get("template_name"));
            context.put("reports", normalizedReports);
            context.put("forum_logs", forumLogs);
            context.put("theme_tokens", layoutDesign.getOrDefault("themeTokens", defaultThemeTokens()));
            context.put("layout", layoutDesign);
            context.put("template_overview", templateOverview);
            context.put("chapter_directives", chapterPlanMap);
            context.put("word_plan", wordPlan);
            context.put("max_tokens", Math.min(config.getMaxContentLength(), 6000));

            // 6. 初始化存储
            Map<String, Object> manifestMeta = new HashMap<>();
            manifestMeta.put("query", query);
            manifestMeta.put("title", layoutDesign.getOrDefault("title", query + " - 舆情洞察报告"));
            manifestMeta.put("subtitle", layoutDesign.get("subtitle"));
            manifestMeta.put("tagline", layoutDesign.get("tagline"));
            manifestMeta.put("templateName", templateResult.get("template_name"));
            manifestMeta.put("selectionReason", templateResult.get("selection_reason"));
            manifestMeta.put("themeTokens", context.get("theme_tokens"));
            
            Map<String, Object> toc = new HashMap<>();
            toc.put("depth", 3);
            toc.put("autoNumbering", true);
            toc.put("title", layoutDesign.getOrDefault("tocTitle", "目录"));
            if (layoutDesign.containsKey("tocPlan")) {
                toc.put("customEntries", layoutDesign.get("tocPlan"));
            }
            manifestMeta.put("toc", toc);
            
            manifestMeta.put("hero", layoutDesign.get("hero"));
            manifestMeta.put("layoutNotes", layoutDesign.get("layoutNotes"));
            manifestMeta.put("wordPlan", Map.of(
                    "totalWords", wordPlan.get("totalWords"),
                    "globalGuidelines", wordPlan.get("globalGuidelines")
            ));
            manifestMeta.put("templateOverview", templateOverview);

            chapterStorage.createManifest(reportId, manifestMeta);
            emit.accept("stage", Map.of("stage", "storage_ready", "report_id", reportId));

            // 7. 逐章生成
            List<Map<String, Object>> chapters = new ArrayList<>();
            int totalChapters = sections.size();
            int completedChapters = 0;

            for (TemplateSection section : sections) {
                logger.info("生成章节: {}", section.getTitle());
                emit.accept("chapter_status", Map.of(
                        "chapterId", section.getChapterId(),
                        "title", section.getTitle(),
                        "status", "running"
                ));

                BiConsumer<String, Map<String, Object>> chunkCallback = (delta, meta) -> {
                    emit.accept("chapter_chunk", Map.of(
                            "chapterId", meta.getOrDefault("chapterId", section.getChapterId()),
                            "title", meta.getOrDefault("title", section.getTitle()),
                            "delta", delta
                    ));
                };

                Map<String, Object> chapterPayload = null;
                int attempt = 1;
                int maxAttempts = Math.max(CONTENT_SPARSE_MIN_ATTEMPTS, config.getChapterJsonMaxAttempts());

                while (attempt <= maxAttempts) {
                    try {
                        chapterPayload = chapterGenerationNode.run(new ChapterGenerationNode.Input(
                                section,
                                context,
                                reportId,
                                chunkCallback,
                                new HashMap<>()
                        ));
                        break;
                    } catch (Exception e) {
                        logger.warn("章节 {} 生成失败 (第 {}/{} 次尝试): {}", section.getTitle(), attempt, maxAttempts, e.getMessage());
                        
                        String statusValue = attempt < maxAttempts ? "retrying" : "error";
                        emit.accept("chapter_status", Map.of(
                                "chapterId", section.getChapterId(),
                                "title", section.getTitle(),
                                "status", statusValue,
                                "attempt", attempt,
                                "error", e.getMessage()
                        ));

                        if (attempt >= maxAttempts) {
                            throw new RuntimeException("章节生成失败: " + e.getMessage(), e);
                        }
                        attempt++;
                    }
                }

                chapters.add(chapterPayload);
                completedChapters++;
                int progress = 20 + (int) Math.round(80.0 * completedChapters / totalChapters);
                emit.accept("progress", Map.of(
                        "progress", progress,
                        "message", String.format("章节 %d/%d 已完成", completedChapters, totalChapters)
                ));
                emit.accept("chapter_status", Map.of(
                        "chapterId", section.getChapterId(),
                        "title", section.getTitle(),
                        "status", "completed",
                        "attempt", attempt
                ));
            }

            // 8. 装订与渲染
            Map<String, Object> documentIr = documentComposer.buildDocument(reportId, manifestMeta, chapters);
            emit.accept("stage", Map.of("stage", "chapters_compiled", "chapter_count", chapters.size()));

            // TODO: HTML Renderer 尚未实现，暂时跳过渲染
            // String htmlReport = renderer.render(documentIr);
            String htmlReport = "<html><body><h1>Report Placeholder</h1></body></html>"; // 占位
            emit.accept("stage", Map.of("stage", "html_rendered", "html_length", htmlReport.length()));

            stateManager.updateHtmlContent(reportId, htmlReport);
            stateManager.updateState(reportId, state -> state.markCompleted());

            // 9. 返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("html_content", htmlReport);
            result.put("report_id", reportId);
            result.put("document_ir", documentIr);
            
            logger.info("报告生成完成: {}", reportId);
            return result;

        } catch (Exception e) {
            stateManager.markTaskFailed(reportId, e.getMessage());
            logger.error("报告生成过程中发生错误: {}", e.getMessage(), e);
            emit.accept("error", Map.of("stage", "agent_failed", "message", e.getMessage()));
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> selectTemplate(String query, List<Object> reports, String forumLogs, String customTemplate) {
        if (customTemplate != null && !customTemplate.isEmpty()) {
            logger.info("使用用户自定义模板");
            return Map.of(
                    "template_name", "custom",
                    "template_content", customTemplate,
                    "selection_reason", "用户指定的自定义模板"
            );
        }

        try {
            return templateSelectionNode.run(new TemplateSelectionNode.Input(query, reports, forumLogs));
        } catch (Exception e) {
            logger.error("模板选择失败，使用默认模板: {}", e.getMessage());
            return getFallbackTemplate();
        }
    }

    private Map<String, Object> getFallbackTemplate() {
        return Map.of(
                "template_name", "社会公共热点事件分析报告模板",
                "template_content", getFallbackTemplateContent(),
                "selection_reason", "模板选择失败，使用默认社会热点事件分析模板"
        );
    }

    private String getFallbackTemplateContent() {
        return """
# 社会公共热点事件分析报告

## 执行摘要
本报告针对当前社会热点事件进行综合分析，整合了多方信息源的观点和数据。

## 事件概况
### 基本信息
- 事件性质：{event_nature}
- 发生时间：{event_time}
- 涉及范围：{event_scope}

## 舆情态势分析
### 整体趋势
{sentiment_analysis}

### 主要观点分布
{opinion_distribution}

## 媒体报道分析
### 主流媒体态度
{media_analysis}

### 报道重点
{report_focus}

## 社会影响评估
### 直接影响
{direct_impact}

### 潜在影响
{potential_impact}

## 应对建议
### 即时措施
{immediate_actions}

### 长期策略
{long_term_strategy}

## 结论与展望
{conclusion}

---
*报告类型：社会公共热点事件分析*
*生成时间：{generation_time}*
""";
    }

    private List<TemplateSection> sliceTemplate(String templateMarkdown) {
        List<TemplateSection> sections = TemplateParser.parseTemplateSections(templateMarkdown);
        if (!sections.isEmpty()) {
            return sections;
        }
        logger.warn("模板未解析出章节，使用默认章节骨架");
        TemplateSection fallback = new TemplateSection(
                "1.0 综合分析",
                "section-1-0",
                10,
                1,
                "1.0 综合分析",
                "1.0"
        );
        fallback.setChapterId("S1");
        fallback.setOutline(List.of("1.1 摘要", "1.2 数据亮点", "1.3 风险提示"));
        return List.of(fallback);
    }

    private Map<String, Object> buildTemplateOverview(String templateMarkdown, List<TemplateSection> sections) {
        String title = extractTemplateTitle(templateMarkdown, !sections.isEmpty() ? sections.get(0).getTitle() : "");
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (TemplateSection section : sections) {
            Map<String, Object> chapter = new HashMap<>();
            chapter.put("chapterId", section.getChapterId());
            chapter.put("title", section.getTitle());
            chapter.put("rawTitle", section.getRawTitle());
            chapter.put("number", section.getNumber());
            chapter.put("slug", section.getSlug());
            chapter.put("order", section.getOrder());
            chapter.put("depth", section.getDepth());
            chapter.put("outline", section.getOutline());
            chapters.add(chapter);
        }
        return Map.of("title", title, "chapters", chapters);
    }

    private String extractTemplateTitle(String templateMarkdown, String fallback) {
        for (String line : templateMarkdown.split("\\r?\\n")) {
            String stripped = line.trim();
            if (stripped.isEmpty()) continue;
            if (stripped.startsWith("#")) {
                return stripped.replaceAll("^#+\\s*", "").trim();
            }
            if (!stripped.isEmpty()) {
                fallback = fallback.isEmpty() ? stripped : fallback;
            }
        }
        return !fallback.isEmpty() ? fallback : "智能舆情分析报告";
    }

    private Map<String, String> normalizeReports(List<Object> reports) {
        Map<String, String> normalized = new HashMap<>();
        String[] keys = {"query_engine", "media_engine"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Object value = (i < reports.size()) ? reports.get(i) : "";
            normalized.put(key, stringify(value));
        }
        return normalized;
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String) return (String) value;
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> defaultThemeTokens() {
        Map<String, Object> tokens = new HashMap<>();
        tokens.put("colors", Map.of(
                "bg", "#f8f9fa",
                "text", "#212529",
                "primary", "#007bff",
                "secondary", "#6c757d",
                "card", "#ffffff",
                "border", "#dee2e6",
                "accent1", "#17a2b8",
                "accent2", "#28a745",
                "accent3", "#ffc107",
                "accent4", "#dc3545"
        ));
        tokens.put("fonts", Map.of(
                "body", "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif",
                "heading", "'Source Han Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif"
        ));
        tokens.put("spacing", Map.of("container", "1200px", "gutter", "24px"));
        tokens.put("vars", Map.of(
                "header_sticky", true,
                "toc_depth", 3,
                "enable_dark_mode", true
        ));
        return tokens;
    }
}
