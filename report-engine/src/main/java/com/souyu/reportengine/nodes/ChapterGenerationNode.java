package com.souyu.reportengine.nodes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.reportengine.core.ChapterStorage;
import com.souyu.reportengine.core.TemplateSection;
import com.souyu.reportengine.prompt.prompts;
import com.souyu.reportengine.schema.Schema;
import com.souyu.reportengine.schema.Validator;
import com.souyu.reportengine.utils.ChartRepairer;
import com.souyu.reportengine.utils.JsonParser;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 负责按章节调用LLM并校验JSON结构。
 * <p>
 * 核心能力：
 * - 构造章节级 payload 与提示词；
 * - 以流式形式写入 MongoDB rawContent 并透传 delta；
 * - 尝试修复/解析LLM输出，并使用 IRValidator 校验；
 * - 对block结构做容错修复，确保最终JSON可渲染。
 */
@Component
public class ChapterGenerationNode extends BaseNode<ChapterGenerationNode.Input, Map<String, Object>> {

    @Autowired
    private Validator validator;
    @Autowired
    private ChapterStorage storage;
    @Autowired
    private JsonParser jsonParser;
    @Autowired
    private ChartRepairer chartRepairer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path errorLogDir;
    private int failedBlockCounter = 0;
    private String activeRunId;
    private final Map<String, String> archivedFailedJson = new HashMap<>();

    public ChapterGenerationNode() {
        super( "ChapterGenerationNode");
        this.errorLogDir = Path.of("logs/json_repair_failures");
        try {
            Files.createDirectories(this.errorLogDir);
        } catch (IOException e) {
            logger.error("Failed to create error log directory", e);
        }
    }

    public static class Input {
        public TemplateSection section;
        public Map<String, Object> context;
        public String reportId;
        public BiConsumer<String, Map<String, Object>> streamCallback;
        public Map<String, Object> kwargs;

        public Input(TemplateSection section, Map<String, Object> context, String reportId, BiConsumer<String, Map<String, Object>> streamCallback, Map<String, Object> kwargs) {
            this.section = section;
            this.context = context;
            this.reportId = reportId;
            this.streamCallback = streamCallback;
            this.kwargs = kwargs;
        }
    }

    @Override
    public Map<String, Object> run(Input input) {
        TemplateSection section = input.section;
        Map<String, Object> context = input.context;
        String reportId = input.reportId;
        BiConsumer<String, Map<String, Object>> streamCallback = input.streamCallback;
        Map<String, Object> kwargs = input.kwargs;

        Map<String, Object> chapterMeta = new HashMap<>();
        chapterMeta.put("chapterId", section.getChapterId());
        chapterMeta.put("slug", section.getSlug());
        chapterMeta.put("title", section.getTitle());
        chapterMeta.put("order", section.getOrder());

        storage.initChapter(reportId, chapterMeta);
        ensureRunState(reportId);

        Map<String, Object> llmPayload = buildPayload(section, context);
        String userMessage;
        try {
            userMessage = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmPayload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize LLM payload", e);
        }

        String rawText = streamLlm(userMessage, reportId, section.getChapterId(), streamCallback, chapterMeta, kwargs);

        List<String> parseContext = new ArrayList<>();
        boolean placeholderCreated = false;
        Map<String, Object> chapterJson;

        try {
            // 使用 JsonParser 进行鲁棒解析
            chapterJson = jsonParser.parse(rawText, "ChapterJSON", List.of("chapter", "blocks", "chapterId", "title"), "chapter");
        } catch (Exception parseError) {
            logger.warn("{} 章节JSON解析失败: {}", section.getTitle(), parseError.getMessage());
            parseContext.add(parseError.getMessage());
            archiveFailedOutput(section, rawText);
            
            // 尝试跨引擎修复 (暂未实现多引擎，仅占位)
            Map<String, Object> recovered = null; 

            if (recovered != null) {
                chapterJson = recovered;
                logger.info("{} 章节JSON已通过跨引擎修复", section.getTitle());
            } else {
                Map.Entry<Map<String, Object>, List<String>> placeholder = buildPlaceholderChapter(section, rawText, parseError);
                if (placeholder == null) {
                    throw new RuntimeException(parseError);
                }
                chapterJson = placeholder.getKey();
                parseContext.addAll(placeholder.getValue());
                placeholderCreated = true;
            }
        }

        chapterJson.putIfAbsent("chapterId", section.getChapterId());
        chapterJson.putIfAbsent("anchor", section.getSlug());
        chapterJson.putIfAbsent("title", section.getTitle());
        chapterJson.putIfAbsent("order", section.getOrder());
        
        // 清理和修复 Block 结构，包括图表修复
        sanitizeChapterBlocks(chapterJson);

        Validator.Result validationResult = validator.validateChapter(chapterJson);
        if (!validationResult.isValid() && !validationResult.getErrors().isEmpty()) {
            Map<String, Object> repaired = attemptLlmStructuralRepair(chapterJson, validationResult.getErrors(), rawText);
            if (repaired != null) {
                chapterJson = repaired;
                chapterJson.putIfAbsent("chapterId", section.getChapterId());
                chapterJson.putIfAbsent("anchor", section.getSlug());
                chapterJson.putIfAbsent("title", section.getTitle());
                chapterJson.putIfAbsent("order", section.getOrder());
                sanitizeChapterBlocks(chapterJson);
                validationResult = validator.validateChapter(chapterJson);
            }
        }

        Exception contentError = null;
        if (validationResult.isValid() && !placeholderCreated) {
            try {
                ensureContentDensity(chapterJson);
            } catch (Exception exc) {
                contentError = exc;
            }
        }

        List<String> errorMessages = new ArrayList<>(parseContext);
        if (!validationResult.isValid()) {
            errorMessages.addAll(validationResult.getErrors());
        }
        if (contentError != null) {
            errorMessages.add(contentError.getMessage());
        }

        storage.saveChapter(reportId, chapterMeta, chapterJson, errorMessages.isEmpty() ? null : errorMessages);

        if (!validationResult.isValid()) {
            throw new RuntimeException(section.getTitle() + " 章节JSON校验失败: " + String.join("; ", validationResult.getErrors().subList(0, Math.min(5, validationResult.getErrors().size()))));
        }
        if (contentError != null) {
            throw new RuntimeException(contentError);
        }

        return chapterJson;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPayload(TemplateSection section, Map<String, Object> context) {
        Map<String, Object> reports = (Map<String, Object>) context.getOrDefault("reports", new HashMap<>());
        Map<String, Object> chapterPlanMap = (Map<String, Object>) context.getOrDefault("chapter_directives", new HashMap<>());
        Map<String, Object> chapterPlan = (Map<String, Object>) chapterPlanMap.getOrDefault(section.getChapterId(), new HashMap<>());

        Map<String, Object> payload = new HashMap<>();
        
        Map<String, Object> sectionMap = new HashMap<>();
        sectionMap.put("chapterId", section.getChapterId());
        sectionMap.put("title", section.getTitle());
        sectionMap.put("slug", section.getSlug());
        sectionMap.put("order", section.getOrder());
        sectionMap.put("number", section.getNumber());
        sectionMap.put("outline", section.getOutline());
        payload.put("section", sectionMap);

        Map<String, Object> globalContext = new HashMap<>();
        globalContext.put("query", context.get("query"));
        globalContext.put("templateName", context.get("template_name"));
        globalContext.put("themeTokens", context.getOrDefault("theme_tokens", new HashMap<>()));
        globalContext.put("styleDirectives", context.getOrDefault("style_directives", new HashMap<>()));
        globalContext.put("layout", context.get("layout"));
        globalContext.put("templateOverview", context.getOrDefault("template_overview", new HashMap<>()));
        payload.put("globalContext", globalContext);

        Map<String, Object> reportsMap = new HashMap<>();
        reportsMap.put("query_engine", reports.getOrDefault("query_engine", ""));
        reportsMap.put("media_engine", reports.getOrDefault("media_engine", ""));
        reportsMap.put("insight_engine", reports.getOrDefault("insight_engine", ""));
        payload.put("reports", reportsMap);

        payload.put("forumLogs", context.getOrDefault("forum_logs", ""));
        payload.put("dataBundles", context.getOrDefault("data_bundles", new ArrayList<>()));

        Map<String, Object> constraints = new HashMap<>();
        constraints.put("language", "zh-CN");
        constraints.put("maxTokens", context.getOrDefault("max_tokens", 4096));
        constraints.put("allowedBlocks", Schema.ALLOWED_BLOCK_TYPES);
        
        Map<String, Object> styleHints = new HashMap<>();
        styleHints.put("expectWidgets", true);
        styleHints.put("forceHeadingAnchors", true);
        styleHints.put("allowInlineMix", true);
        constraints.put("styleHints", styleHints);
        
        payload.put("constraints", constraints);
        payload.put("chapterPlan", chapterPlan);
        payload.put("wordPlan", context.get("word_plan"));

        if (!chapterPlan.isEmpty()) {
            if (chapterPlan.containsKey("targetWords")) constraints.put("wordTarget", chapterPlan.get("targetWords"));
            if (chapterPlan.containsKey("minWords")) constraints.put("minWords", chapterPlan.get("minWords"));
            if (chapterPlan.containsKey("maxWords")) constraints.put("maxWords", chapterPlan.get("maxWords"));
            if (chapterPlan.containsKey("emphasis")) constraints.put("emphasis", chapterPlan.get("emphasis"));
            if (chapterPlan.containsKey("sections")) {
                constraints.put("sectionBudgets", chapterPlan.get("sections"));
                globalContext.put("sectionBudgets", chapterPlan.get("sections"));
            }
        }

        return payload;
    }

    private String streamLlm(String userMessage, String reportId, String chapterId, BiConsumer<String, Map<String, Object>> streamCallback, Map<String, Object> sectionMeta, Map<String, Object> kwargs) {
        StringBuilder chunks = new StringBuilder();
        
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(prompts.SYSTEM_PROMPT_CHAPTER_JSON),
                new UserMessage(userMessage)
        ));

        try (Writer writer = storage.getStreamWriter(reportId, chapterId)) {
            llmClient.stream(prompt)
                    .map(response -> {
                        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                            return response.getResult().getOutput().getContent();
                        }
                        return "";
                    })
                    .doOnNext(delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            chunks.append(delta);
                            try {
                                writer.write(delta);
                                if (streamCallback != null) {
                                    streamCallback.accept(delta, sectionMeta != null ? sectionMeta : new HashMap<>());
                                }
                            } catch (IOException e) {
                                logger.warn("章节流式写入失败: {}", e.getMessage());
                            }
                        }
                    })
                    .blockLast();
            
            writer.flush();
        } catch (IOException e) {
            logger.error("流式写入器关闭失败", e);
        }

        return chunks.toString();
    }

    private void ensureRunState(String runId) {
        if (runId.equals(activeRunId)) {
            return;
        }
        activeRunId = runId;
        archivedFailedJson.clear();
    }

    private void archiveFailedOutput(TemplateSection section, String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        archivedFailedJson.put(section.getChapterId(), rawText);
    }

    private String getArchivedFailedOutput(TemplateSection section) {
        return archivedFailedJson.get(section.getChapterId());
    }

    private Map.Entry<Map<String, Object>, List<String>> buildPlaceholderChapter(TemplateSection section, String rawText, Exception parseError) {
        String snapshot = getArchivedFailedOutput(section);
        if (snapshot == null) snapshot = rawText;
        
        Map<String, String> logRef = persistErrorPayload(section, snapshot, parseError);
        if (logRef == null) {
            logger.error("{} 章节JSON完全损坏且无法写入日志", section.getTitle());
            return null;
        }

        String importance = isSectionCritical(section) ? "critical" : "standard";
        String message = String.format("LLM返回块解析错误，详情请见 %s 的 %s 记录。", logRef.get("relativeFile"), logRef.get("entryId"));

        Map<String, Object> headingBlock = new HashMap<>();
        headingBlock.put("type", "heading");
        headingBlock.put("level", "critical".equals(importance) ? 2 : 3);
        headingBlock.put("text", section.getTitle());
        headingBlock.put("anchor", section.getSlug());

        Map<String, Object> calloutBlock = new HashMap<>();
        calloutBlock.put("type", "callout");
        calloutBlock.put("tone", "critical".equals(importance) ? "danger" : "warning");
        calloutBlock.put("title", "LLM返回块解析错误");
        
        Map<String, Object> paraBlock = new HashMap<>();
        paraBlock.put("type", "paragraph");
        Map<String, Object> inline = new HashMap<>();
        inline.put("text", message);
        paraBlock.put("inlines", List.of(inline));
        calloutBlock.put("blocks", List.of(paraBlock));

        Map<String, Object> meta = new HashMap<>();
        meta.put("errorLogRef", logRef);
        meta.put("rawJsonPreview", (snapshot != null ? snapshot : "").substring(0, Math.min(2000, (snapshot != null ? snapshot : "").length())));
        meta.put("errorMessage", message);
        meta.put("importance", importance);
        calloutBlock.put("meta", meta);

        Map<String, Object> placeholder = new HashMap<>();
        placeholder.put("chapterId", section.getChapterId());
        placeholder.put("title", section.getTitle());
        placeholder.put("anchor", section.getSlug());
        placeholder.put("order", section.getOrder());
        placeholder.put("blocks", List.of(headingBlock, calloutBlock));
        placeholder.put("errorPlaceholder", true);

        List<String> errors = List.of(String.format("%s 章节JSON解析失败，已降级为占位。参考 %s#%s", section.getTitle(), logRef.get("relativeFile"), logRef.get("entryId")));

        return Map.entry(placeholder, errors);
    }

    private Map<String, String> persistErrorPayload(TemplateSection section, String rawText, Exception parseError) {
        try {
            failedBlockCounter++;
            String entryId = String.format("E%04d", failedBlockCounter);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String slug = section.getSlug() != null ? section.getSlug() : "section";
            String filename = String.format("%s-%s-%s.json", timestamp, slug, entryId);
            Path filePath = errorLogDir.resolve(filename);

            Map<String, Object> payload = new HashMap<>();
            payload.put("chapterId", section.getChapterId());
            payload.put("title", section.getTitle());
            payload.put("slug", section.getSlug());
            payload.put("order", section.getOrder());
            payload.put("rawOutput", rawText);
            payload.put("error", parseError.toString());
            payload.put("loggedAt", timestamp);

            Files.writeString(filePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

            String relativePath;
            try {
                relativePath = Path.of(".").toAbsolutePath().relativize(filePath.toAbsolutePath()).toString();
            } catch (IllegalArgumentException e) {
                relativePath = filePath.toString();
            }

            return Map.of(
                    "file", filePath.toString(),
                    "relativeFile", relativePath,
                    "entryId", entryId,
                    "timestamp", timestamp
            );
        } catch (Exception exc) {
            logger.error("记录章节JSON错误日志失败: {}", exc.getMessage());
            return null;
        }
    }

    private boolean isSectionCritical(TemplateSection section) {
        if (section == null) return false;
        if (section.getDepth() <= 2) return true;
        String number = section.getNumber();
        if (number != null && number.chars().filter(ch -> ch == '.').count() <= 1) return true;
        return false;
    }

    private Map<String, Object> attemptLlmStructuralRepair(Map<String, Object> chapter, List<String> validationErrors, String rawText) {
        if (validationErrors == null || validationErrors.isEmpty()) return null;
        
        try {
            // 构造修复 prompt
            String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "failedChapter", chapter,
                    "validatorErrors", validationErrors,
                    "rawOutputTail", (rawText != null && rawText.length() > 2000) ? rawText.substring(rawText.length() - 2000) : (rawText != null ? rawText : "")
            ));

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(prompts.SYSTEM_PROMPT_CHAPTER_JSON_REPAIR),
                    new UserMessage(payloadJson)
            ));

            String response = llmClient.streamAndCollect(prompt).block();
            if (response == null || response.isEmpty()) return null;

            return jsonParser.parse(response, "LLMRepair", List.of("chapter"), "chapter");

        } catch (Exception e) {
            logger.error("章节JSON LLM修复调用失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void sanitizeChapterBlocks(Map<String, Object> chapter) {
        Object blocksObj = chapter.get("blocks");
        if (blocksObj instanceof List) {
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) blocksObj;
            walkBlocks(blocks);
        }
    }

    @SuppressWarnings("unchecked")
    private void walkBlocks(List<Map<String, Object>> blocks) {
        if (blocks == null) return;
        for (Map<String, Object> block : blocks) {
            ensureBlockType(block);
            sanitizeBlockContent(block);
            
            String type = (String) block.get("type");
            if ("list".equals(type)) {
                Object itemsObj = block.get("items");
                if (itemsObj instanceof List) {
                    // 递归处理 list items
                    // 这里简化处理，假设 items 已经是规范化的
                }
            } else if (Set.of("callout", "blockquote", "engineQuote").contains(type)) {
                walkBlocks((List<Map<String, Object>>) block.get("blocks"));
            } else if ("table".equals(type)) {
                // 处理 table rows
                // 暂略
            } else if ("widget".equals(type)) {
                // 使用 ChartRepairer 修复图表
                ChartRepairer.RepairResult result = chartRepairer.repair(block, null);
                if (result.isSuccess()) {
                    block.putAll(result.getRepairedBlock());
                }
            } else {
                Object nested = block.get("blocks");
                if (nested instanceof List) {
                    walkBlocks((List<Map<String, Object>>) nested);
                }
            }
        }
    }

    private void ensureBlockType(Map<String, Object> block) {
        String type = (String) block.get("type");
        if (type != null && Schema.ALLOWED_BLOCK_TYPES.contains(type)) {
            return;
        }
        String text = "";
        for (String key : List.of("text", "content", "title")) {
            Object val = block.get(key);
            if (val instanceof String && !((String) val).trim().isEmpty()) {
                text = ((String) val).trim();
                break;
            }
        }
        if (text.isEmpty()) {
            try {
                text = objectMapper.writeValueAsString(block);
            } catch (JsonProcessingException e) {
                text = block.toString();
            }
        }
        block.clear();
        block.put("type", "paragraph");
        Map<String, Object> inline = new HashMap<>();
        inline.put("text", text);
        inline.put("marks", new ArrayList<>());
        block.put("inlines", List.of(inline));
    }

    private void sanitizeBlockContent(Map<String, Object> block) {
        String type = (String) block.get("type");
        if ("paragraph".equals(type)) {
            normalizeParagraphBlock(block);
        } else if ("engineQuote".equals(type)) {
            sanitizeEngineQuoteBlock(block);
        }
    }

    private void normalizeParagraphBlock(Map<String, Object> block) {
        Object inlinesObj = block.get("inlines");
        if (!(inlinesObj instanceof List)) {
            String text = extractBlockText(block);
            Map<String, Object> inline = new HashMap<>();
            inline.put("text", text);
            inline.put("marks", new ArrayList<>());
            block.put("inlines", List.of(inline));
        }
    }

    private void sanitizeEngineQuoteBlock(Map<String, Object> block) {
        Object engineObj = block.get("engine");
        String engine = (engineObj instanceof String) ? ((String) engineObj).toLowerCase() : null;
        if (!Schema.ENGINE_AGENT_TITLES.containsKey(engine)) {
            engine = "insight";
        }
        block.put("engine", engine);
        block.put("title", Schema.ENGINE_AGENT_TITLES.get(engine));
    }

    private String extractBlockText(Map<String, Object> block) {
        for (String key : List.of("text", "content", "value", "title")) {
            Object val = block.get(key);
            if (val instanceof String) return (String) val;
            if (val != null) return val.toString();
        }
        return "";
    }

    private void ensureContentDensity(Map<String, Object> chapter) {
        Object blocksObj = chapter.get("blocks");
        if (!(blocksObj instanceof List) || ((List<?>) blocksObj).isEmpty()) {
            throw new RuntimeException("章节缺少正文区块，无法输出内容");
        }
    }
}
