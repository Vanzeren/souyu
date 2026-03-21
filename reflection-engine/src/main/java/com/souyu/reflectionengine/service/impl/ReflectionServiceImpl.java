package com.souyu.reflectionengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.bocha.BochaClient;
import com.souyu.common.bocha.model.BochaResponse;
import com.souyu.common.bocha.model.WebResult;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.dto.SourceItem;
import com.souyu.common.dto.reflection.ReflectionRequest;
import com.souyu.common.dto.reflection.ReflectionResponse;
import com.souyu.common.tavily.TavilyClient;
import com.souyu.common.tavily.model.TavilyResponse;
import com.souyu.reflectionengine.dto.JudgeResult;
import com.souyu.reflectionengine.prompt.ReflectionJudgePrompt;
import com.souyu.reflectionengine.service.ReflectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reflection Engine 服务实现。
 *
 * <p>核心逻辑（双视角交叉验证）：
 * <ol>
 *   <li>接收 {@link ReflectionRequest}，包含 AbstractAgent 实际使用的搜索结果</li>
 *   <li>使用<em>另一种</em>搜索引擎（Tavily/Bocha）独立搜索，获得补充视角</li>
 *   <li>将两种搜索结果一并送入 Prompt：
 *     <ul>
 *       <li>Set A: AbstractAgent 实际使用的搜索结果</li>
 *       <li>Set B: ReflectionEngine 用另一种引擎搜到的结果（用于发现遗漏）</li>
 *     </ul>
 *   </li>
 *   <li>LLM 评判 {@link #currentSummary} 是否充分利用了 Set A，以及 Set B 中有哪些重要遗漏</li>
 *   <li>输出改进建议和下一轮的搜索方向（针对 Set B 中的高价值遗漏）</li>
 * </ol>
 *
 * <p>运行在 Java 21 虚拟线程上（由 Tomcat 的 VirtualThreadTaskExecutor 调度）。
 */
@Slf4j
@Service
public class ReflectionServiceImpl implements ReflectionService {

    @Autowired
    private TimeContextChatClient chatClient;

    @Autowired(required = false)
    private TavilyClient tavilyClient;

    @Autowired(required = false)
    private BochaClient bochaClient;

    @Value("${app.reflection.quality-threshold:0.75}")
    private double qualityThreshold;

    @Value("${app.reflection.search.max-results:5}")
    private int maxReflectionSearchResults;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ReflectionResponse evaluate(ReflectionRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("[ReflectionService] 开始评判 taskId={} 段落='{}' 轮次={}/{}",
                request.taskId(), request.paragraphTitle(),
                request.reflectionRound() + 1, request.maxReflections());
        log.info("[ReflectionService] AbstractAgent 使用 {}, Reflection 将使用 {} 进行补充验证",
                request.agentSearchEngine(), request.getReflectionSearchEngine());

        try {
            // 1. AbstractAgent 实际使用的搜索结果（Set A）
            List<SourceItem> agentResults = request.agentSearchResults() != null
                    ? request.agentSearchResults()
                    : List.of();

            // 2. ReflectionEngine 用另一种引擎独立搜索（Set B）
            List<SourceItem> reflectionResults = performSupplementarySearch(request);

            // 3. 构建对比分析的 Prompt
            BeanOutputConverter<JudgeResult> converter = new BeanOutputConverter<>(JudgeResult.class);

            String promptContent = new PromptTemplate(ReflectionJudgePrompt.JUDGE_PROMPT)
                    .create(Map.of(
                            "paragraph_title", safe(request.paragraphTitle()),
                            "paragraph_expected", safe(request.paragraphExpected()),
                            "current_summary", safe(request.currentSummary()),
                            "agent_search_results", formatAgentResults(agentResults),
                            "reflection_search_results", formatReflectionResults(reflectionResults),
                            "reflection_round", String.valueOf(request.reflectionRound() + 1),
                            "max_reflections", String.valueOf(request.maxReflections()),
                            "quality_threshold", String.valueOf(qualityThreshold),
                            "output_schema", converter.getFormat()
                    ))
                    .getContents();

            int promptLength = promptContent.length();
            log.info("[ReflectionService] Prompt构建完成: taskId={}, Agent结果={}, Reflection结果={}, Prompt长度={}字符",
                    request.taskId(), agentResults.size(), reflectionResults.size(), promptLength);

            // 4. 调用 LLM 评判（使用流式传输避免超时）
            long llmStartTime = System.currentTimeMillis();
            String jsonResponse = chatClient
                    .streamAndCollect(new Prompt(promptContent))
                    .block(); // 等待流式响应完成
            long llmDuration = System.currentTimeMillis() - llmStartTime;

            log.info("[ReflectionService] LLM调用完成: taskId={}, LLM耗时={}ms, 响应长度={}字符",
                    request.taskId(), llmDuration,
                    jsonResponse != null ? jsonResponse.length() : 0);

            if (jsonResponse == null || jsonResponse.isBlank()) {
                log.warn("[ReflectionService] LLM返回空响应，采用默认继续策略: taskId={}", request.taskId());
                return defaultContinueResponse(request);
            }

            // 5. 解析并处理结果
            JudgeResult judgeResult = parseJudgeResult(converter, jsonResponse, request);
            return processJudgeResult(judgeResult, request, startTime);

        } catch (org.springframework.web.client.RestClientException e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("[ReflectionService] LLM API 调用失败，降级为默认策略: taskId={}, 总耗时={}ms, 错误={}",
                    request.taskId(), totalDuration, e.getMessage());
            return defaultContinueResponse(request);
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("[ReflectionService] 评判异常: taskId={}, 总耗时={}ms, 错误={}",
                    request.taskId(), totalDuration, e.getMessage(), e);
            return defaultContinueResponse(request);
        }
    }

    /**
     * 使用另一种搜索引擎进行补充搜索，用于发现 AbstractAgent 可能遗漏的信息。
     */
    private List<SourceItem> performSupplementarySearch(ReflectionRequest request) {
        String searchQuery = request.toSearchQuery();
        String engine = request.getReflectionSearchEngine();
        log.info("[ReflectionService] 开始补充搜索: taskId={}, engine='{}', query='{}'",
                request.taskId(), engine, searchQuery);

        List<SourceItem> results = new ArrayList<>();

        try {
            if (request.useTavilyForReflection() && tavilyClient != null) {
                TavilyResponse response = tavilyClient.basicSearchNews(searchQuery, maxReflectionSearchResults);
                if (response != null && response.getResults() != null) {
                    results = response.getResults().stream()
                            .map(r -> new SourceItem(
                                    r.getTitle(), r.getUrl(), r.getContent(),
                                    r.getScore(), r.getPublishedDate(), "Tavily"
                            ))
                            .collect(Collectors.toList());
                }
            } else if (request.useBochaForReflection() && bochaClient != null) {
                BochaResponse response = bochaClient.webSearchOnly(searchQuery, maxReflectionSearchResults);
                if (response != null && response.getWebpages() != null) {
                    results = response.getWebpages().stream()
                            .map(r -> new SourceItem(
                                    r.getName(), r.getUrl(), r.getSnippet(),
                                    null, r.getDatePublished(), "Bocha"
                            ))
                            .collect(Collectors.toList());
                }
            }

            log.info("[ReflectionService] 补充搜索完成: taskId={}, engine='{}', 结果数={}",
                    request.taskId(), engine, results.size());
        } catch (Exception e) {
            log.warn("[ReflectionService] 补充搜索失败: taskId={}, engine='{}', error={}",
                    request.taskId(), engine, e.getMessage());
        }

        return results;
    }

    /**
     * 格式化 AbstractAgent 的搜索结果（Set A）。
     */
    private String formatAgentResults(List<SourceItem> results) {
        if (results == null || results.isEmpty()) {
            return "（无搜索结果）";
        }
        return formatResultsInternal(results, "【当前实际使用的搜索结果】");
    }

    /**
     * 格式化 ReflectionEngine 的补充搜索结果（Set B）。
     */
    private String formatReflectionResults(List<SourceItem> results) {
        if (results == null || results.isEmpty()) {
            return "（补充搜索未返回结果）";
        }
        return formatResultsInternal(results, "【补充搜索发现的潜在信息】");
    }

    private static final int MAX_CONTENT_LENGTH = 120;

    private String formatResultsInternal(List<SourceItem> results, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(" (").append(results.size()).append("条)\n");

        for (int i = 0; i < results.size(); i++) {
            SourceItem item = results.get(i);
            sb.append(i + 1).append(". ");
            if (item.title() != null) sb.append(item.title()).append("\n");
            if (item.content() != null) {
                String content = item.content();
                if (content.length() > MAX_CONTENT_LENGTH) content = content.substring(0, MAX_CONTENT_LENGTH) + "...";
                sb.append("   ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 处理 LLM 评判结果，应用早停逻辑。
     */
    private ReflectionResponse processJudgeResult(JudgeResult judgeResult,
                                                   ReflectionRequest request,
                                                   long startTime) {
        boolean shouldContinue = judgeResult.shouldContinue();
        String stopReason = null;

        if (judgeResult.qualityScore() >= qualityThreshold) {
            shouldContinue = false;
            stopReason = "质量分达标";
            log.info("[ReflectionService] 质量分{} >= 阈值{}，强制早停: taskId={}",
                    String.format("%.2f", judgeResult.qualityScore()),
                    qualityThreshold, request.taskId());
        }
        if (request.reflectionRound() >= request.maxReflections() - 1) {
            shouldContinue = false;
            stopReason = "已达最大轮次";
            log.info("[ReflectionService] 已达最大轮次{}，强制早停: taskId={}",
                    request.maxReflections(), request.taskId());
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        log.info("[ReflectionService] 评判完成: taskId={}, 总分={:.2f}, 早停={}, 原因={}, 总耗时={}ms",
                request.taskId(), judgeResult.qualityScore(),
                !shouldContinue, stopReason != null ? stopReason : "继续搜索", totalDuration);

        if (shouldContinue && judgeResult.nextSearchFocus() != null) {
            log.info("[ReflectionService] 下一轮搜索方向: taskId={}, focus='{}'",
                    request.taskId(), judgeResult.nextSearchFocus());
        }

        return new ReflectionResponse(
                shouldContinue,
                judgeResult.qualityScore(),
                judgeResult.identifiedGaps() != null ? judgeResult.identifiedGaps() : List.of(),
                judgeResult.nextSearchFocus(),
                judgeResult.reflectionSummary()
        );
    }

    /**
     * 解析 LLM 输出，带 fallback 降级。
     */
    private JudgeResult parseJudgeResult(BeanOutputConverter<JudgeResult> converter,
                                          String jsonResponse,
                                          ReflectionRequest request) {
        try {
            return converter.convert(jsonResponse);
        } catch (Exception e) {
            log.warn("[ReflectionService] BeanOutputConverter解析失败，尝试手动解析: taskId={}, error={}",
                    request.taskId(), e.getMessage());
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMap = objectMapper.readValue(jsonResponse, Map.class);
            double qs = toDouble(rawMap.get("quality_score"), 0.5);
            boolean sc = toBoolean(rawMap.get("should_continue"), true);
            @SuppressWarnings("unchecked")
            List<String> gaps = rawMap.get("identified_gaps") instanceof List<?> gapList
                    ? (List<String>) gapList : new ArrayList<>();
            String focus = rawMap.get("next_search_focus") instanceof String s ? s : "";
            String summary = rawMap.get("reflection_summary") instanceof String s ? s : "";
            return new JudgeResult(qs, sc, gaps, focus, summary, 0, 0, 0, 0, 0);
        } catch (Exception ex) {
            log.error("[ReflectionService] 手动JSON解析也失败: taskId={}, error={}",
                    request.taskId(), ex.getMessage());
            return new JudgeResult(0.5, true, List.of(), "", "解析失败，采用默认策略", 0, 0, 0, 0, 0);
        }
    }

    private ReflectionResponse defaultContinueResponse(ReflectionRequest request) {
        boolean shouldContinue = request.reflectionRound() < request.maxReflections() - 1;
        return new ReflectionResponse(shouldContinue, 0.5, List.of(), null,
                "Reflection 服务异常，采用默认策略");
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private double toDouble(Object value, double defaultVal) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private boolean toBoolean(Object value, boolean defaultVal) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultVal;
    }
}
