package com.souyu.common.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.context.SearchContext;
import com.souyu.common.manager.StateManager;
import com.souyu.common.manager.TaskControlManager;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.node.querynode.QueryFormattingNode;
import com.souyu.common.node.querynode.ReportStructureNode;
import com.souyu.common.producer.messageProducer;
import com.souyu.common.state.Paragraph;
import com.souyu.common.state.State;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.prompt.DeepSearchPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractAgent<R> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAgent.class);

    @Autowired
    private TimeContextChatClient chatClient; // 使用 TimeContextChatClient

    @Autowired
    private ReportStructureNode reportStructureNode;

    @Autowired
    private QueryFormattingNode queryFormattingNode;


    protected abstract AgentConfig getAgentConfig();

    // 保留 getToolNames()，用于动态注册工具
    protected abstract String[] getToolNames();

    @Autowired
    private StateManager stateManager;

    @Autowired
    private TaskStatusManager taskStatusManager;

    @Autowired
    private TaskControlManager taskControlManager;

    @Autowired
    private messageProducer producer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async("taskExecutor")
    public void research(String query, boolean saveReport, String taskId) {
        // 显式初始化 State，确保 taskId 和 query 被正确记录
        // stateManager.initState(taskId, query); // 移除显式初始化，避免覆盖

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
        // 如果需要支持自定义 Prompt，可以在这里处理，但目前统一使用 DeepSearchPrompts

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

        // 1. 首次搜索 (Function Calling)
        // 使用 DeepSearchPrompts
        String firstSearchPromptTemplate = DeepSearchPrompts.SYSTEM_PROMPT_FIRST_SEARCH;
        String firstSearchPrompt = new PromptTemplate(firstSearchPromptTemplate)
                .create(Map.of(
                        "input_schema", "{\"title\": \"" + paragraphSnapshot.getTitle() + "\", \"content\": \"" + paragraphSnapshot.getContent() + "\"}",
                        // 移除 tool_description
                        "current_date", LocalDate.now().toString()
                ))
                .getContents();

        // Step 1: 调用工具获取信息
        String searchResult = performSearch(taskId, paragraphSnapshot, firstSearchPrompt);

        // 捕获并保存结构化搜索结果
        captureSearchResults(taskId, paragraphIndex, "First Search");

        // 2. 首次总结 (Text Generation)
        String firstSummaryPromptTemplate = DeepSearchPrompts.SYSTEM_PROMPT_FIRST_SUMMARY;
        String firstSummaryPrompt = new PromptTemplate(firstSummaryPromptTemplate)
                .create(Map.of(
                        "input_schema", "{\"title\": \"" + paragraphSnapshot.getTitle() + "\", \"content\": \"" + paragraphSnapshot.getContent() + "\"}",
                        "output_schema", DeepSearchPrompts.OUTPUT_SCHEMA_FIRST_SUMMARY
                ))
                .getContents();

        // 将搜索结果附带在 Prompt 中
        String finalSummaryPrompt = firstSummaryPrompt + "\n\n以下是搜索结果：\n" + searchResult;

        // 调用 LLM 生成总结 (不带工具)
        String firstSummaryJson = chatClient.call(new org.springframework.ai.chat.prompt.Prompt(finalSummaryPrompt)).getResult().getOutput().getContent();

        // 解析 JSON 获取 content
        String firstSummaryContent = extractContentFromJson(firstSummaryJson);
        // 添加日志输出
        logger.info("段落 {} 首次总结:\n{}", paragraphIndex + 1, firstSummaryContent);

        stateManager.executeUpdate(taskId, s -> s.getParagraph(paragraphIndex).getResearch().setLatestSummary(firstSummaryContent));
        producer.sendMessage("forum", Map.of("taskId", taskId, "content", firstSummaryContent, "engine", engineName()));

        // 3. 反思循环
        String currentSummary = firstSummaryContent;
        for (int i = 0; i < getAgentConfig().getSearch().getMaxReflections(); i++) {
            checkIfCancelled(taskId);
            logger.info("  - 反思 {}/{}", i + 1, getAgentConfig().getSearch().getMaxReflections());

            String reflectionPromptTemplate = DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION;
            String reflectionPrompt = new PromptTemplate(reflectionPromptTemplate)
                    .create(Map.of(
                            "input_schema", "{\"title\": \"" + paragraphSnapshot.getTitle() + "\", \"paragraph_latest_state\": \"" + currentSummary + "\"}",
                            // 移除 tool_description
                            "current_date", LocalDate.now().toString()
                    ))
                    .getContents();

            // Step 1: 调用工具获取补充信息
            String reflectionSearchResult = performSearch(taskId, paragraphSnapshot, reflectionPrompt);

            // 捕获并保存结构化搜索结果
            captureSearchResults(taskId, paragraphIndex, "Reflection Search " + (i + 1));

            // Step 2: 使用专门的 Prompt 生成更新后的总结
            String reflectionSummaryPromptTemplate = DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION_SUMMARY;
            String reflectionSummaryPrompt = new PromptTemplate(reflectionSummaryPromptTemplate)
                    .create(Map.of(
                            "input_schema", "{\"title\": \"" + paragraphSnapshot.getTitle() + "\", \"paragraph_latest_state\": \"" + currentSummary + "\"}"
                    ))
                    .getContents();

            String finalReflectionSummaryPrompt = reflectionSummaryPrompt + "\n\n以下是补充搜索结果：\n" + reflectionSearchResult;

            String updatedSummaryJson = chatClient.call(new org.springframework.ai.chat.prompt.Prompt(finalReflectionSummaryPrompt)).getResult().getOutput().getContent();
            String updatedSummaryContent = extractContentFromJson(updatedSummaryJson);

            // 添加日志输出
            logger.info("段落 {} 反思 {} 更新总结:\n{}", paragraphIndex + 1, i + 1, updatedSummaryContent);

            currentSummary = updatedSummaryContent;
            stateManager.executeUpdate(taskId, s -> s.getParagraph(paragraphIndex).getResearch().setLatestSummary(updatedSummaryContent));

            if (i == getAgentConfig().getSearch().getMaxReflections() - 1) {
                producer.sendMessage("forum", Map.of("taskId", taskId, "content", updatedSummaryContent, "engine", engineName()));
            }
        }

        stateManager.executeUpdate(taskId, s -> s.getParagraph(paragraphIndex).getResearch().markCompleted());
        logger.info("段落 {} 处理完成", paragraphIndex + 1);
    }

    // 重命名为 performSearch，只负责搜索
    private String performSearch(String taskId, Paragraph paragraph, String userPrompt) {
        // 清理上下文，防止污染
        SearchContext.clear();

        try {
            // 使用 TimeContextChatClient 的 callWithFunctions 方法
            // 这样可以复用时间注入逻辑，并且支持 Function Calling
            ChatResponse chatResponse = chatClient.callWithFunctions(
                    new org.springframework.ai.chat.prompt.Prompt(userPrompt),
                    getToolNames() // 使用动态工具列表
            );

            // 记录 Reasoning (思考过程)
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

    // 捕获并保存结构化搜索结果
    private void captureSearchResults(String taskId, Integer paragraphIndex, String queryDescription) {
        // 从 SearchContext 获取原始 Response
        R rawResponse = (R) SearchContext.getLastResponse();
        if (rawResponse != null) {
            // 提取结构化数据
            List<Map<String, Object>> results = extractSearchResults(rawResponse);
            if (results != null && !results.isEmpty()) {
                stateManager.executeUpdate(taskId, s -> {
                    s.getParagraph(paragraphIndex).getResearch().addSearchResults(queryDescription, results);
                });
                logger.info("Captured {} structured search results for paragraph {}", results.size(), paragraphIndex + 1);
            }
        } else {
            logger.warn("No structured search results captured for paragraph {}", paragraphIndex + 1);
        }
        // 清理上下文
        SearchContext.clear();
    }

    // 简单的 JSON 提取辅助方法 (假设 LLM 返回的是 {"paragraph_latest_state": "..."} 或 {"updated_paragraph_latest_state": "..."})
    private String extractContentFromJson(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            if (map.containsKey("paragraph_latest_state")) {
                return (String) map.get("paragraph_latest_state");
            } else if (map.containsKey("updated_paragraph_latest_state")) {
                return (String) map.get("updated_paragraph_latest_state");
            }
            // 如果不是 JSON 或没有特定字段，直接返回原始文本
            return json;
        } catch (Exception e) {
            // 解析失败，说明 LLM 可能直接返回了文本，或者 JSON 格式有误
            // 为了鲁棒性，直接返回原始文本
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

    // 辅助方法：获取进度摘要 (如果需要)
    public Map<String, Object> getProgressSummary(String taskId) {
        State state = stateManager.getState(taskId);
        if (state != null) {
            return state.getProgressSummary();
        }
        return new HashMap<>();
    }

    // 辅助方法：提取搜索结果 (如果需要)
    protected abstract List<Map<String, Object>> extractSearchResults(R response);

    // 辅助方法：执行搜索工具 (如果需要，虽然现在主要靠 Function Calling)
    public abstract R executeSearchTool(String toolName, String query, Map<String, Object> kwargs);
}
