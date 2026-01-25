package com.souyu.common.agent;

import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.manager.TaskControlManager;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.node.querynode.*;
import com.souyu.common.producer.messageProducer;
import com.souyu.common.state.Paragraph;
import com.souyu.common.state.State;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.manager.StateManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

public abstract class AbstractAgent<R, C> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAgent.class);

    @Autowired
    private TimeContextChatClient chatClient;

    @Autowired
    private FirstSearchNode firstSearchNode;

    @Autowired
    private FirstSummaryNode firstSummaryNode;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private ReflectionNode reflectionNode;

    @Autowired
    private ReflectionSummaryNode reflectionSummaryNode;

    @Autowired
    private ReportStructureNode reportStructureNode;

    @Autowired
    private QueryFormattingNode queryFormattingNode;

    // 使用抽象的 AgentConfig，而不是具体的 QueryEngineConfig
    protected abstract AgentConfig getAgentConfig();

    /**
     * 获取 Prompt 映射的方法，允许子类覆盖默认 Prompt
     * 支持的 key 包括:
     * - "report_structure": 生成报告结构的 Prompt
     * - "first_search": 首次搜索的 Prompt
     * - "first_summary": 首次总结的 Prompt
     * - "reflection": 反思阶段的 Prompt
     * - "reflection_summary": 反思总结阶段的 Prompt
     * - "report_formatting": 最终报告格式化的 Prompt
     */
    protected Map<String, String> getPrompts() {
        return new HashMap<>();
    }

    @Autowired
    private StateManager stateManager;
    
    @Autowired
    private TaskStatusManager taskStatusManager; // 注入新的状态管理器
    
    @Autowired
    private TaskControlManager taskControlManager; // 注入任务控制管理器

    @Autowired
    private messageProducer producer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行深度研究
     */
    @Async("taskExecutor")
    public void research(String query, boolean saveReport, String taskId) {
        logger.info("\n============================================================");
        logger.info("开始深度研究: {}", query);
        logger.info("============================================================");

        try {
            // 检查取消状态
            checkIfCancelled(taskId);
            
            // Step 1: 生成报告结构
            stateManager.executeUpdate(taskId, state -> generateReportStructure(state, query));

            // 获取当前状态以遍历段落
            State currentState = stateManager.getState(taskId);
            int totalParagraphs = currentState.getParagraphs().size();

            // Step 2: 处理每个段落
            for (int i = 0; i < totalParagraphs; i++) {
                checkIfCancelled(taskId); // 每次循环前检查
                
                // 发送心跳：更新任务时间，防止超时
                taskStatusManager.refreshUpdateTime(taskId);

                try {
                    processParagraph(taskId, i);
                } catch (Exception e) {
                    if (e instanceof RuntimeException && "Task cancelled".equals(e.getMessage())) {
                        throw e;
                    }
                    logger.error("处理段落 {} 失败: {}", i + 1, e.getMessage(), e);
                    // 继续处理下一个段落，不中断整个任务
                }
            }

            checkIfCancelled(taskId);

            // Step 3: 生成最终报告
            String finalReport = stateManager.executeUpdateAndReturn(taskId, this::generateFinalReport);

            // Step 4: 保存报告 (到 MinIO)
            if (saveReport) {
                // 再次获取最新状态以保存
                State finalState = stateManager.getState(taskId);
                saveReport(taskId, finalState, finalReport,engineName());
            }

            // 标记报告已完成 (发送 WORKER_COMPLETED 事件)
            // 移除直接更新状态的调用
            // taskStatusManager.updateWorkerStatus(taskId, engineName(), TaskStatus.WorkerStatus.COMPLETED);
            producer.triggerMasterReport(taskId, engineName());

            logger.info("\n============================================================");
            logger.info("深度研究完成！");
            logger.info("============================================================");

        } catch (Exception e) {
            if ("Task cancelled".equals(e.getMessage())) {
                logger.warn("Task {} was cancelled.", taskId);
                // 可以选择更新状态为 FAILED 或 CANCELLED (如果支持)
                // 这里简单处理，不发送完成事件
            } else {
                logger.error("深度研究过程中发生错误: {}", e.getMessage(), e);
                stateManager.markTaskFailed(taskId, e.getMessage());
                
                // 发送 WORKER_FAILED 事件 (需要 messageProducer 支持，或者复用 triggerMasterReport 并带上状态)
                // 这里暂时使用 triggerMasterReport，但 Master 需要能识别失败
                // 更好的做法是新增一个 triggerWorkerFailed 方法
                // 暂时保留旧逻辑，或者我们修改 messageProducer
                
                // 既然要彻底解耦，我们应该发送 WORKER_FAILED
                // 但为了不改动太多，我们先移除直接更新状态的调用
                // taskStatusManager.updateWorkerStatus(taskId, engineName(), TaskStatus.WorkerStatus.FAILED);
                
                // 发送失败事件 (需要 messageProducer 支持)
                Map<String, String> event = new HashMap<>();
                event.put("taskId", taskId);
                event.put("type", "WORKER_FAILED");
                event.put("source", engineName());
                event.put("error", e.getMessage());
                producer.sendMessage("task:events:stream", event);
            }
        } finally {
            // 清理取消状态
            taskControlManager.clearTask(taskId);
        }
    }
    
    private void checkIfCancelled(String taskId) {
        if (taskControlManager.isCancelled(taskId)) {
            throw new RuntimeException("Task cancelled");
        }
    }

    public boolean validateDateFormat(String date) {
        if (date == null || date.isEmpty()) {
            return false;
        }

        String pattern = "^\\d{4}-\\d{2}-\\d{2}$";

        if (!date.matches(pattern)) {
            return false;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDate.parse(date, formatter);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 根据工具名称执行相应的搜索工具
     */
    public abstract R executeSearchTool(String toolName, String query, Map<String, Object> kwargs);

    /**
     * 注入engine名
     */
    public abstract String engineName();

    /**
     * 从搜索结果中提取标准化的结果列表
     */
    protected abstract List<Map<String, Object>> extractSearchResults(R response);

    /**
     * 生成报告结构
     */
    public void generateReportStructure(State state, String query) {
        logger.info("正在生成报告结构...");

        // 传递自定义 prompt
        Map<String, Object> kwargs = new HashMap<>();
        String customPrompt = getPrompts().get("report_structure");
        if (customPrompt != null) {
            kwargs.put("system_prompt", customPrompt);
        }

        state = reportStructureNode.mutateState(query, state, kwargs);

        StringBuilder message = new StringBuilder();
        message.append("报告结构已生成，共 ").append(state.getParagraphs().size()).append(" 个段落:");

        int i = 1;
        for (Paragraph paragraph : state.getParagraphs()) {
            message.append("\n  ").append(i++).append(". ").append(paragraph.getTitle());
        }
        logger.info(message.toString());

    }

    /**
     * 处理单个段落
     */
    public void processParagraph(String taskId, Integer paragraphIndex) {
        checkIfCancelled(taskId);
        
        State stateSnapshot = stateManager.getState(taskId);
        if (stateSnapshot == null) return;
        Paragraph paragraphSnapshot = stateSnapshot.getParagraph(paragraphIndex);
        if (paragraphSnapshot == null) return;

        logger.info("开始处理段落 {}: {}", paragraphIndex + 1, paragraphSnapshot.getTitle());

        String searchQuery = paragraphSnapshot.getTitle();
        R firstSearchResponse = null;

        try {
            Map<String, Object> inputMap = new HashMap<>();
            inputMap.put("title", paragraphSnapshot.getTitle());
            inputMap.put("content", paragraphSnapshot.getContent());
            String inputData = objectMapper.writeValueAsString(inputMap);

            // 传递自定义 prompt
            Map<String, Object> kwargs = new HashMap<>();
            String customPrompt = getPrompts().get("first_search");
            if (customPrompt != null) {
                kwargs.put("system_prompt", customPrompt);
            }

            Map<String, String> firstResult = firstSearchNode.run(inputData, kwargs);
            searchQuery = firstResult.getOrDefault("search_query", paragraphSnapshot.getTitle());
            String toolName = firstResult.getOrDefault("search_tool", "basic_search_news");
            logger.info("  首次搜索查询: {}", searchQuery);

            firstSearchResponse = executeSearchTool(toolName, searchQuery, Map.of("max_results", 5));

        } catch (Exception e) {
            logger.error("首次搜索失败: {}", e.getMessage());
        }

        final String finalSearchQuery = searchQuery;
        final R finalResponse = firstSearchResponse;

        stateManager.executeUpdate(taskId, state -> {
            Paragraph p = state.getParagraph(paragraphIndex);
            if (p != null) {
                updateParagraphResearch(p, finalSearchQuery, finalResponse, taskId);
            }
        });

        reflectionLoop(taskId, paragraphIndex);

        stateManager.executeUpdate(taskId, state -> {
            Paragraph p = state.getParagraph(paragraphIndex);
            if (p != null) {
                p.getResearch().markCompleted();
            }
        });

        logger.info("段落 {} 处理完成", paragraphIndex + 1);
    }

    /**
     * 反思循环
     */
    public void reflectionLoop(String taskId, Integer paragraphIndex) {
        int maxReflections = getAgentConfig().getSearch().getMaxReflections();

        for (int i = 0; i < maxReflections; i++) {
            checkIfCancelled(taskId);
            logger.info("  - 反思 {}/{}", i + 1, maxReflections);

            try {
                State stateSnapshot = stateManager.getState(taskId);
                Paragraph paragraphSnapshot = stateSnapshot.getParagraph(paragraphIndex);

                Map<String, Object> map = new HashMap<>();
                map.put("title", paragraphSnapshot.getTitle());
                map.put("content", paragraphSnapshot.getContent());
                map.put("paragraph_latest_state", paragraphSnapshot.getResearch().getLatestSummary());

                String inputData = objectMapper.writeValueAsString(map);

                // 传递自定义 prompt
                Map<String, Object> kwargs = new HashMap<>();
                String customPrompt = getPrompts().get("reflection");
                if (customPrompt != null) {
                    kwargs.put("system_prompt", customPrompt);
                }

                Map<String, String> reflectionOutput = reflectionNode.run(inputData, kwargs);
                String searchQuery = reflectionOutput.get("search_query");
                String searchTool = reflectionOutput.getOrDefault("search_tool", "basic_search_news");
                String reasoning = reflectionOutput.get("reasoning");

                logger.info("   反思查询: {}", searchQuery);
                logger.info("   选择的工具: {}", searchTool);
                logger.info("   反思推理: {}", reasoning);

                if (searchQuery == null || searchQuery.isBlank()) {
                    logger.warn("    反思未生成有效查询，跳过本次循环");
                    continue;
                }

                Map<String, Object> searchKwargs = new HashMap<>();
                searchKwargs.put("max_results", 5);

                if ("search_news_by_date".equals(searchTool)) {
                    String startDate = reflectionOutput.get("start_date");
                    String endDate = reflectionOutput.get("end_date");

                    if (startDate != null && endDate != null) {
                        if (validateDateFormat(startDate) && validateDateFormat(endDate)) {
                            searchKwargs.put("start_date", startDate);
                            searchKwargs.put("end_date", endDate);
                            logger.info("    时间范围: {} 到 {}", startDate, endDate);
                        } else {
                            logger.warn("    ⚠️  日期格式错误，改用基础搜索");
                            searchTool = "basic_search_news";
                        }
                    } else {
                        logger.warn("    ⚠️  缺少时间参数，改用基础搜索");
                        searchTool = "basic_search_news";
                    }
                }

                R response = executeSearchTool(searchTool, searchQuery, searchKwargs);

                List<Map<String, Object>> searchResults = new ArrayList<>();
                List<Map<String, Object>> extractedResults = extractSearchResults(response);

                if (extractedResults != null) {
                    int maxResults = Math.min(extractedResults.size(), 10);
                    List<Map<String, Object>> results = extractedResults.subList(0, maxResults);

                    logger.info("    找到 {} 个反思搜索结果", results.size());

                    for (int j = 0; j < results.size(); j++) {
                        Map<String, Object> result = results.get(j);
                        searchResults.add(result);

                        String title = (String) result.getOrDefault("title", "");
                        String dateInfo = result.get("published_date") != null ? " (发布于: " + result.get("published_date") + ")" : "";
                        logger.info("      {}. {}...{}", j + 1, title.length() > 50 ? title.substring(0, 50) : title, dateInfo);
                    }
                } else {
                    logger.info("    未找到反思搜索结果");
                }

                stateManager.executeUpdate(taskId, state -> {
                    Paragraph p = state.getParagraph(paragraphIndex);
                    if (p != null) {
                        p.getResearch().addSearchResults(searchQuery, searchResults);
                    }
                });

                String currentSummary = paragraphSnapshot.getResearch().getLatestSummary();

                Map<String, Object> summaryInput = new HashMap<>();
                summaryInput.put("title", paragraphSnapshot.getTitle());
                summaryInput.put("content", paragraphSnapshot.getContent());
                summaryInput.put("search_query", searchQuery);
                summaryInput.put("search_results", formatSearchResultsForPrompt(searchResults, getAgentConfig().getSearch().getContentMaxLength()));
                summaryInput.put("paragraph_latest_state", currentSummary);

                String summaryInputJson = objectMapper.writeValueAsString(summaryInput);


                // 传递自定义 prompt
                Map<String, Object> summaryKwargs = new HashMap<>();
                String customSummaryPrompt = getPrompts().get("reflection_summary");
                if (customSummaryPrompt != null) {
                    summaryKwargs.put("system_prompt", customSummaryPrompt);
                }

                String updatedSummary = reflectionSummaryNode.run(summaryInputJson, summaryKwargs);

                // 将总结内容压入消息队列
                if (i == maxReflections - 1) {
                    Map<String, String> toForum = new HashMap<>();
                    toForum.put("taskId", taskId);
                    toForum.put("content", updatedSummary);
                    toForum.put("engine",engineName());
                    producer.sendMessage("forum", toForum);
                }
                stateManager.executeUpdate(taskId, state -> {
                            Paragraph p = state.getParagraph(paragraphIndex);
                            if (p != null) {
                                p.getResearch().setLatestSummary(updatedSummary);
                                p.getResearch().incrementReflection();
                            }
                        }
                );

                logger.info("    反思 {} 完成", i + 1);

            } catch (Exception e) {
                logger.error("    反思循环发生错误: {}", e.getMessage());
            }
        }
    }

    /**
     * 生成最终报告
     */
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
            // 直接使用手动格式化，避免一次性发送大量文本给 LLM 导致 Token 超限
            // 之前的逻辑是调用 queryFormattingNode.run()，这会将所有段落内容合并发送给 LLM
            // 改为手动拼接，既高效又安全
            finalReport = queryFormattingNode.formatReportManually(reportData, state.getReportTitle());
            logger.info("已使用手动拼接方式生成最终报告，避免 Token 超限。");

        } catch (Exception e) {
            logger.error("生成最终报告失败: {}", e.getMessage());
            // Fallback: 简单的字符串拼接
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

    /*
     * 保存报告到 MinIO
     */
    public void saveReport(String taskId, State state, String reportContent,String engine) {
        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String querySafe = state.getQuery().replaceAll("[^a-zA-Z0-9\\s\\-_]", "").trim().replace(' ', '_');
        if (querySafe.length() > 30) {
            querySafe = querySafe.substring(0, 30);
        }

        String filename = String.format("deep_search_report_%s_%s.md", querySafe, timestamp);

        try {
            // 保存报告到 MinIO
            stateManager.saveContentToMinio(reportContent, filename,engine,taskId);
            logger.info("报告已保存到 MinIO: {}", filename);

            // 状态已经由 StateManager 自动保存，这里不需要额外操作
            // 但如果需要保存一个特定时间点的快照，可以另存一份
            if (getAgentConfig().getOutput().isSaveIntermediateStates()) {
                // StateManager 已经在每次 update 时保存了最新的 state.json
                // 这里我们只是确认一下，或者可以保存一个带时间戳的副本
                logger.info("状态已自动保存到 MinIO: states/{}.json", taskId);
            }
        } catch (Exception e) {
            logger.error("保存报告失败: {}", e.getMessage());
        }
    }

    /**
     * 获取进度摘要
     */
    public Map<String, Object> getProgressSummary(String taskId) {
        State state = stateManager.getState(taskId);
        if (state != null) {
            return state.getProgressSummary();
        }
        return new HashMap<>();
    }

    private void updateParagraphResearch(Paragraph paragraph, String query, R response, String taskId) {
        List<Map<String, Object>> results = extractSearchResults(response);
        if (results == null || results.isEmpty()) return;

        paragraph.getResearch().addSearchResults(query, results);

        // 生成首次总结
        try {
            Map<String, Object> summaryInput = new HashMap<>();
            summaryInput.put("title", paragraph.getTitle());
            summaryInput.put("content", paragraph.getContent());
            summaryInput.put("search_query", query);
            summaryInput.put("search_results", formatSearchResultsForPrompt(results, getAgentConfig().getSearch().getContentMaxLength()));

            String summaryInputJson = objectMapper.writeValueAsString(summaryInput);

            // 传递自定义 prompt
            Map<String, Object> kwargs = new HashMap<>();
            String customPrompt = getPrompts().get("first_summary");
            if (customPrompt != null) {
                kwargs.put("system_prompt", customPrompt);
            }

            String summary = firstSummaryNode.run(summaryInputJson, kwargs);

            paragraph.getResearch().setLatestSummary(summary);

            Map<String, String> toForum = new HashMap<>();
            toForum.put("taskId", taskId);
            toForum.put("content", summary);
            toForum.put("engine",engineName());
            producer.sendMessage("forum", toForum);

        } catch (Exception e) {
            logger.error("生成首次总结失败: {}", e.getMessage());
        }
    }

    private String formatSearchResultsForPrompt(List<Map<String, Object>> results, int maxLength) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> result : results) {
            sb.append("Title: ").append(result.get("title")).append("\n");
            sb.append("URL: ").append(result.get("url")).append("\n");
            sb.append("Date: ").append(result.getOrDefault("published_date", "N/A")).append("\n");
            sb.append("Content: ").append(result.get("content")).append("\n\n");

            if (sb.length() > maxLength) {
                return sb.substring(0, maxLength) + "... (truncated)";
            }
        }
        return sb.toString();
    }
}
