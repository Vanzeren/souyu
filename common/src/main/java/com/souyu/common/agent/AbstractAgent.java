package com.souyu.common.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.client.ReflectionEngineClient;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.context.SearchContext;
import com.souyu.common.dto.SourceItem;
import com.souyu.common.dto.SummaryResult;
import com.souyu.common.dto.UpdatedSummaryResult;
import com.souyu.common.dto.reflection.ReflectionRequest;
import com.souyu.common.dto.reflection.ReflectionResponse;
import com.souyu.common.manager.StateManager;
import com.souyu.common.manager.TaskControlManager;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.node.querynode.QueryFormattingNode;
import com.souyu.common.node.querynode.ReportStructureNode;
import com.souyu.common.producer.messageProducer;
import com.souyu.common.state.Paragraph;
import com.souyu.common.state.Search;
import com.souyu.common.state.State;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.prompt.DeepSearchPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractAgent<R> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAgent.class);

    @Autowired
    private TimeContextChatClient chatClient;

    @Autowired
    private ReportStructureNode reportStructureNode;

    @Autowired
    private QueryFormattingNode queryFormattingNode;

    protected abstract AgentConfig getAgentConfig();

    protected abstract String[] getToolNames();

    @Autowired
    private StateManager stateManager;

    @Autowired
    private TaskStatusManager taskStatusManager;

    @Autowired
    private TaskControlManager taskControlManager;

    @Autowired
    private messageProducer producer;

    /**
     * Feign client for the reflection-engine microservice.
     * Optional injection: if reflection-engine is not deployed, the agent falls back
     * to the original fixed-iteration behavior (backward-compatible).
     */
    @Autowired(required = false)
    private ReflectionEngineClient reflectionEngineClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async("taskExecutor")
    public void research(String query, boolean saveReport, String taskId) {
        logger.info("============================================================");
        logger.info("开始深度研究: {}", query);
        logger.info("============================================================");

        try {
            checkIfCancelled(taskId);

            stateManager.executeUpdate(taskId, state -> generateReportStructure(state, query));
            taskStatusManager.refreshUpdateTime(taskId);

            State currentState = stateManager.getState(taskId);
            int totalParagraphs = currentState.getParagraphs().size();

            for (int i = 0; i < totalParagraphs; i++) {
                checkIfCancelled(taskId);
                try {
                    processParagraph(taskId, i);
                    taskStatusManager.refreshUpdateTime(taskId);
                } catch (Exception e) {
                    if (e instanceof RuntimeException && "Task cancelled".equals(e.getMessage())) {
                        throw e;
                    }
                    logger.error("处理段落 {} 失败: {}", i + 1, e.getMessage(), e);
                }
            }

            checkIfCancelled(taskId);

            String finalReport = stateManager.executeUpdateAndReturn(taskId, this::generateFinalReport);

            if (saveReport) {
                State finalState = stateManager.getState(taskId);
                saveReport(taskId, finalState, finalReport, engineName());
                taskStatusManager.refreshUpdateTime(taskId);
            }

            producer.triggerMasterReport(taskId, engineName());

            logger.info("============================================================");
            logger.info("深度研究完成！");
            logger.info("============================================================");

        } catch (Exception e) {
            if ("Task cancelled".equals(e.getMessage())) {
                logger.warn("Task {} was cancelled.", taskId);
            } else {
                logger.error("深度研究过程中发生错误: {}", e.getMessage(), e);
                stateManager.markTaskFailed(taskId, e.getMessage());

                Map<String, String> event = new HashMap<>();
                event.put("taskId", taskId);
                event.put("type", "WORKER_FAILED");
                event.put("source", engineName());
                event.put("error", e.getMessage());
                producer.sendMessage("task:events:stream", event);
            }
        } finally {
            taskControlManager.clearTask(taskId);
        }
    }

    private void checkIfCancelled(String taskId) {
        if (taskControlManager.isCancelled(taskId, engineName())) {
            throw new RuntimeException("Task cancelled");
        }
    }

    public abstract String engineName();

    public void generateReportStructure(State state, String query) {
        logger.info("正在生成报告结构...");
        Map<String, Object> kwargs = new HashMap<>();
        state = reportStructureNode.mutateState(query, state, kwargs);

        StringBuilder message = new StringBuilder();
        message.append("报告结构已生成，共 ").append(state.getParagraphs().size()).append(" 个段落:");
        int i = 1;
        for (Paragraph paragraph : state.getParagraphs()) {
            message.append("\n  ").append(i++).append(". ").append(paragraph.getTitle());
        }
        logger.info(message.toString());
    }

    public void processParagraph(String taskId, Integer paragraphIndex) {
        checkIfCancelled(taskId);

        State stateSnapshot = stateManager.getState(taskId);
        if (stateSnapshot == null) return;
        Paragraph paragraphSnapshot = stateSnapshot.getParagraph(paragraphIndex);
        if (paragraphSnapshot == null) return;

        logger.info("开始处理段落 {}: {}", paragraphIndex + 1, paragraphSnapshot.getTitle());

        // 准备基础输入数据
        Map<String, String> baseInputMap = new HashMap<>();
        baseInputMap.put("title", paragraphSnapshot.getTitle());
        baseInputMap.put("content", paragraphSnapshot.getContent());

        // 1. 首次搜索 (Function Calling)
        String firstSearchPrompt = new PromptTemplate(DeepSearchPrompts.SYSTEM_PROMPT_FIRST_SEARCH)
                .create(Map.of(
                        "input_schema", toJson(baseInputMap),
                        "current_date", LocalDate.now().toString()
                ))
                .getContents();

        String searchResult = performSearch(taskId, paragraphSnapshot, firstSearchPrompt);
        captureSearchResults(taskId, paragraphIndex, "First Search");

        // 2. 首次总结 (Text Generation)
        String firstSummaryContent = generateContent(
                DeepSearchPrompts.SYSTEM_PROMPT_FIRST_SUMMARY,
                baseInputMap,
                searchResult,
                SummaryResult.class,
                SummaryResult::paragraph_latest_state
        );

        logger.info("段落 {} 首次总结:\n{}", paragraphIndex + 1, firstSummaryContent);
        updateParagraphSummary(taskId, paragraphIndex, firstSummaryContent);
        
        // 修复 NPE: 确保 content 不为 null
        String safeFirstSummaryContent = firstSummaryContent != null ? firstSummaryContent : "";
        producer.sendMessage("forum", Map.of("taskId", taskId, "content", safeFirstSummaryContent, "engine", engineName()));

        // 3. 反思循环
        String currentSummary = safeFirstSummaryContent;
        int maxReflections = getAgentConfig().getSearch().getMaxReflections();

        for (int i = 0; i < maxReflections; i++) {
            checkIfCancelled(taskId);
            logger.info("  - 反思 {}/{}", i + 1, maxReflections);

            Map<String, String> reflectionInputMap = new HashMap<>();
            reflectionInputMap.put("title", paragraphSnapshot.getTitle());
            reflectionInputMap.put("paragraph_latest_state", currentSummary);

            // Reflection Search
            String reflectionPrompt = new PromptTemplate(DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION)
                    .create(Map.of(
                            "input_schema", toJson(reflectionInputMap),
                            "current_date", LocalDate.now().toString()
                    ))
                    .getContents();

            String reflectionSearchResult = performSearch(taskId, paragraphSnapshot, reflectionPrompt);
            captureSearchResults(taskId, paragraphIndex, "Reflection Search " + (i + 1));

            // =========================================================
            // Reflection Engine 质量评判（LLM-as-Judge）
            // 若 reflection-engine 未部署则跳过，维持原有行为
            // =========================================================
            String nextSearchFocus = null;
            if (reflectionEngineClient != null) {
                try {
                    List<SourceItem> capturedResults = getCapturedSearchResults(taskId, paragraphIndex);
                    ReflectionRequest reflectionRequest = new ReflectionRequest(
                            taskId,
                            paragraphSnapshot.getTitle(),
                            paragraphSnapshot.getContent(),
                            currentSummary,
                            capturedResults,
                            i,
                            maxReflections,
                            engineName()
                    );
                    ReflectionResponse reflectionResponse = reflectionEngineClient.reflect(reflectionRequest);

                    logger.info("  [Reflection Judge] 质量分={} 早停={}",
                            String.format("%.2f", reflectionResponse.qualityScore()),
                            !reflectionResponse.shouldContinue());

                    if (!reflectionResponse.identifiedGaps().isEmpty()) {
                        logger.info("  [Reflection Judge] 知识缺口: {}", reflectionResponse.identifiedGaps());
                    }

                    if (!reflectionResponse.shouldContinue()) {
                        logger.info("  [Reflection Judge] 段落 {} 质量达标，提前结束反思循环", paragraphIndex + 1);
                        // 仍然执行本轮摘要更新后 break
                        nextSearchFocus = null;
                    } else {
                        nextSearchFocus = reflectionResponse.nextSearchFocus();
                    }

                    // 执行本轮 Reflection Summary（无论是否早停都要合并本轮已搜到的内容）
                    String updatedSummaryContent = generateReflectionSummary(
                            reflectionInputMap, reflectionSearchResult, nextSearchFocus);
                    String safeUpdatedSummaryContent = updatedSummaryContent != null ? updatedSummaryContent : "";
                    currentSummary = safeUpdatedSummaryContent;
                    updateParagraphSummary(taskId, paragraphIndex, currentSummary);

                    if (!reflectionResponse.shouldContinue()) {
                        // 早停：发送最终摘要到 forum，然后跳出循环
                        if (i == maxReflections - 1 || !reflectionResponse.shouldContinue()) {
                            producer.sendMessage("forum", Map.of("taskId", taskId, "content", currentSummary, "engine", engineName()));
                        }
                        break;
                    }

                } catch (Exception e) {
                    logger.warn("  [Reflection Judge] 调用 reflection-engine 失败，降级为原有策略: {}", e.getMessage());
                    // 降级：按原有逻辑处理本轮
                    String updatedSummaryContent = generateContent(
                            DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION_SUMMARY,
                            reflectionInputMap,
                            reflectionSearchResult,
                            UpdatedSummaryResult.class,
                            UpdatedSummaryResult::updated_paragraph_latest_state
                    );
                    String safeUpdatedSummaryContent = updatedSummaryContent != null ? updatedSummaryContent : "";
                    currentSummary = safeUpdatedSummaryContent;
                    updateParagraphSummary(taskId, paragraphIndex, currentSummary);
                    if (i == maxReflections - 1) {
                        producer.sendMessage("forum", Map.of("taskId", taskId, "content", currentSummary, "engine", engineName()));
                    }
                }
            } else {
                // reflection-engine 未部署：原有行为
                String updatedSummaryContent = generateContent(
                        DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION_SUMMARY,
                        reflectionInputMap,
                        reflectionSearchResult,
                        UpdatedSummaryResult.class,
                        UpdatedSummaryResult::updated_paragraph_latest_state
                );
                String safeUpdatedSummaryContent = updatedSummaryContent != null ? updatedSummaryContent : "";
                currentSummary = safeUpdatedSummaryContent;
                updateParagraphSummary(taskId, paragraphIndex, currentSummary);
                if (i == maxReflections - 1) {
                    producer.sendMessage("forum", Map.of("taskId", taskId, "content", currentSummary, "engine", engineName()));
                }
            }
        }

        stateManager.executeUpdate(taskId, s -> s.getParagraph(paragraphIndex).getResearch().markCompleted());
        logger.info("段落 {} 处理完成", paragraphIndex + 1);
    }

    // 通用内容生成方法 (使用 BeanOutputConverter)
    private <T> String generateContent(String promptTemplateStr, Map<String, String> inputMap, String searchResult, Class<T> dtoClass, Function<T, String> contentExtractor) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(dtoClass);

        String prompt = new PromptTemplate(promptTemplateStr)
                .create(Map.of(
                        "input_schema", toJson(inputMap),
                        "output_schema", converter.getFormat()
                ))
                .getContents();

        String finalPrompt = prompt + "\n\n以下是搜索结果：\n" + searchResult;
        
        String jsonResponse = null;
        try {
            jsonResponse = chatClient.call(new org.springframework.ai.chat.prompt.Prompt(finalPrompt)).getResult().getOutput().getContent();
        } catch (Exception e) {
            logger.error("LLM call failed", e);
            return ""; // LLM 调用失败，返回空字符串
        }

        if (jsonResponse == null) {
            return "";
        }

        try {
            T result = converter.convert(jsonResponse);
            return contentExtractor.apply(result);
        } catch (Exception e) {
            logger.warn("BeanOutputConverter failed, falling back to manual extraction: {}", e.getMessage());
            // Fallback: 如果解析失败，尝试手动提取，或者直接返回原始文本（如果它看起来像 Markdown）
            String extracted = extractContentFromJson(jsonResponse);
            return extracted != null ? extracted : "";
        }
    }

    private void updateParagraphSummary(String taskId, Integer paragraphIndex, String summary) {
        // 确保 summary 不为 null
        String safeSummary = summary != null ? summary : "";
        stateManager.executeUpdate(taskId, s -> s.getParagraph(paragraphIndex).getResearch().setLatestSummary(safeSummary));
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            logger.error("JSON serialization failed", e);
            return "{}";
        }
    }

    private String performSearch(String taskId, Paragraph paragraph, String userPrompt) {
        SearchContext.clear();
        try {
            ChatResponse chatResponse = chatClient.callWithFunctions(
                    new org.springframework.ai.chat.prompt.Prompt(userPrompt),
                    getToolNames()
            );
            String reasoning = chatResponse.getResult().getOutput().getContent();
            if (reasoning != null && !reasoning.isBlank()) {
                logger.info("Reasoning (思考过程):\n{}", reasoning);
            }
            return reasoning;
        } catch (NonTransientAiException e) {
            logger.error("由于内容安全风险，跳过该段落搜索: {}", e.getMessage());
            return "";
        }
    }

    private void captureSearchResults(String taskId, Integer paragraphIndex, String queryDescription) {
        R rawResponse = (R) SearchContext.getLastResponse();
        if (rawResponse != null) {
            List<SourceItem> results = extractSearchResults(rawResponse);
            if (results != null && !results.isEmpty()) {
                List<Map<String, Object>> resultsMap = results.stream().map(item -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("title", item.title());
                    map.put("url", item.url());
                    map.put("content", item.content());
                    map.put("score", item.score());
                    map.put("published_date", item.publishedDate());
                    map.put("source", item.source());
                    return map;
                }).collect(Collectors.toList());

                stateManager.executeUpdate(taskId, s -> {
                    s.getParagraph(paragraphIndex).getResearch().addSearchResults(queryDescription, resultsMap);
                });
                logger.info("Captured {} structured search results for paragraph {}", results.size(), paragraphIndex + 1);
            }
        } else {
            logger.warn("No structured search results captured for paragraph {}", paragraphIndex + 1);
        }
        SearchContext.clear();
    }

    // 限制传递给 reflection-engine 的搜索结果数量
    private static final int MAX_REFLECTION_SEARCH_RESULTS = 10;

    /**
     * 从 State 的 searchHistory 中提取最近一轮搜索捕获的 SourceItem 列表，
     * 用于构建 ReflectionRequest 中的 searchResults 字段。
     *
     * <p>由于 captureSearchResults() 在每次搜索后追加到 searchHistory，
     * 这里取 history 中最后一批（直到遇到不同的 queryDescription 前缀）。
     * 限制数量避免 reflection-engine 的 prompt 过大。
     */
    private List<SourceItem> getCapturedSearchResults(String taskId, Integer paragraphIndex) {
        State state = stateManager.getState(taskId);
        if (state == null) return List.of();
        Paragraph paragraph = state.getParagraph(paragraphIndex);
        if (paragraph == null || paragraph.getResearch() == null) return List.of();

        List<SourceItem> allResults = paragraph.getResearch().getSearchHistory().stream()
                .map(search -> new SourceItem(
                        search.getTitle(),
                        search.getUrl(),
                        search.getContent(),
                        search.getScore(),
                        search.getTimestamp(),
                        "captured"
                ))
                .collect(Collectors.toList());

        if (allResults.size() > MAX_REFLECTION_SEARCH_RESULTS) {
            logger.info("[AbstractAgent] 搜索结果 {} 条，截取前 {} 条传给 reflection-engine",
                    allResults.size(), MAX_REFLECTION_SEARCH_RESULTS);
            return allResults.subList(0, MAX_REFLECTION_SEARCH_RESULTS);
        }
        return allResults;
    }

    /**
     * 生成反思摘要，将 Reflection Judge 給出的 nextSearchFocus 作为补充约束注入 Prompt 末尾。
     *
     * <p>若 nextSearchFocus 不为空，则在原有 SYSTEM_PROMPT_REFLECTION_SUMMARY 的基础上
     * 追加约束说明，让 LLM 在合并内容时优先关注缺口方向——而不改变 Function Calling 机制。
     *
     * @param reflectionInputMap  包含 title 和 paragraph_latest_state 的输入 Map
     * @param searchResult        本轮搜索返回的推理文本
     * @param nextSearchFocus     Reflection Judge 给出的补充搜索方向（可为 null）
     * @return 更新后的段落摘要内容
     */
    private String generateReflectionSummary(Map<String, String> reflectionInputMap,
                                              String searchResult,
                                              String nextSearchFocus) {
        BeanOutputConverter<UpdatedSummaryResult> converter =
                new BeanOutputConverter<>(UpdatedSummaryResult.class);

        String basePrompt = new PromptTemplate(DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION_SUMMARY)
                .create(Map.of(
                        "input_schema", toJson(reflectionInputMap),
                        "output_schema", converter.getFormat()
                ))
                .getContents();

        // 如果 Reflection Judge 提供了补充方向，追加在 Prompt 末尾作为约束
        String augmentedPrompt = basePrompt;
        if (nextSearchFocus != null && !nextSearchFocus.isBlank()) {
            augmentedPrompt = basePrompt + "\n\n**[Reflection Agent 补充指导]** 在合并和丰富段落内容时，请特别关注以下方向：\n" + nextSearchFocus;
            logger.debug("[AbstractAgent] 注入 Reflection Judge 搜索方向约束: {}", nextSearchFocus);
        }

        String finalPrompt = augmentedPrompt + "\n\n以下是搜索结果：\n" + searchResult;

        String jsonResponse;
        try {
            jsonResponse = chatClient.call(
                    new org.springframework.ai.chat.prompt.Prompt(finalPrompt)
            ).getResult().getOutput().getContent();
        } catch (Exception e) {
            logger.error("generateReflectionSummary LLM call failed", e);
            return "";
        }

        if (jsonResponse == null) return "";

        try {
            UpdatedSummaryResult result = converter.convert(jsonResponse);
            return result.updated_paragraph_latest_state();
        } catch (Exception e) {
            logger.warn("generateReflectionSummary BeanOutputConverter failed, falling back: {}", e.getMessage());
            String extracted = extractContentFromJson(jsonResponse);
            return extracted != null ? extracted : "";
        }
    }

    private String extractContentFromJson(String json) {
        if (json == null) return "";
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            if (map.containsKey("paragraph_latest_state")) {
                return (String) map.get("paragraph_latest_state");
            } else if (map.containsKey("updated_paragraph_latest_state")) {
                return (String) map.get("updated_paragraph_latest_state");
            }
            return json;
        } catch (Exception e) {
            // 如果不是 JSON，直接返回原始文本（假设它是 Markdown）
            return json;
        }
    }

    public String generateFinalReport(State state) {
        logger.info("\n[步骤 3] 生成最终报告...");
        List<Map<String, String>> reportData = new ArrayList<>();
        for (Paragraph paragraph : state.getParagraphs()) {
            Map<String, String> map = new HashMap<>();
            map.put("title", paragraph.getTitle());
            map.put("paragraph_latest_state", paragraph.getResearch().getLatestSummary());
            reportData.add(map);
        }

        String finalReport;
        try {
            finalReport = queryFormattingNode.formatReportManually(reportData, state.getReportTitle());
            logger.info("已使用手动拼接方式生成最终报告，避免 Token 超限。");
        } catch (Exception e) {
            logger.error("生成最终报告失败: {}", e.getMessage());
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(state.getReportTitle()).append("\n\n");
            for (Map<String, String> item : reportData) {
                sb.append("## ").append(item.get("title")).append("\n\n");
                sb.append(item.get("paragraph_latest_state")).append("\n\n");
            }
            finalReport = sb.toString();
        }

        state.setFinalReport(finalReport);
        state.markCompleted();
        logger.info("最终报告生成完成");
        return finalReport;
    }

    public void saveReport(String taskId, State state, String reportContent, String engine) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String querySafe = state.getQuery().replaceAll("[^a-zA-Z0-9\\s\\-_]", "").trim().replace(' ', '_');
        if (querySafe.length() > 30) {
            querySafe = querySafe.substring(0, 30);
        }
        String filename = String.format("deep_search_report_%s_%s.md", querySafe, timestamp);
        try {
            stateManager.saveContentToMinio(reportContent, filename, engine, taskId);
            logger.info("报告已保存到 MinIO: {}", filename);
        } catch (Exception e) {
            logger.error("保存报告失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> getProgressSummary(String taskId) {
        State state = stateManager.getState(taskId);
        if (state != null) {
            return state.getProgressSummary();
        }
        return new HashMap<>();
    }

    protected abstract List<SourceItem> extractSearchResults(R response);

    public abstract R executeSearchTool(String toolName, String query, Map<String, Object> kwargs);
}
