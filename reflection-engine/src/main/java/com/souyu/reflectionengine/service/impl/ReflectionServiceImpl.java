package com.souyu.reflectionengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.dto.SourceItem;
import com.souyu.common.dto.reflection.ReflectionRequest;
import com.souyu.common.dto.reflection.ReflectionResponse;
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
import java.util.List;
import java.util.Map;

/**
 * Reflection Engine 服务实现。
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>将 {@link ReflectionRequest} 格式化为 LLM Judge Prompt</li>
 *   <li>调用 {@link TimeContextChatClient} 获取 LLM 评判结果</li>
 *   <li>用 {@link BeanOutputConverter} 解析 JSON 响应为 {@link JudgeResult}</li>
 *   <li>根据 qualityScore 和 reflectionRound 决定是否早停</li>
 *   <li>返回 {@link ReflectionResponse}</li>
 * </ol>
 *
 * <p>运行在 Java 21 虚拟线程上（由 Tomcat 的 VirtualThreadTaskExecutor 调度），
 * 无需显式配置线程池。
 */
@Slf4j
@Service
public class ReflectionServiceImpl implements ReflectionService {

    @Autowired
    private TimeContextChatClient chatClient;

    @Value("${app.reflection.quality-threshold:0.75}")
    private double qualityThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ReflectionResponse evaluate(ReflectionRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("[ReflectionService] 开始评判 taskId={} 段落='{}' 轮次={}/{}",
                request.taskId(), request.paragraphTitle(),
                request.reflectionRound() + 1, request.maxReflections());

        try {
            // 1. 构建 BeanOutputConverter 以生成 JSON Schema 并解析响应
            BeanOutputConverter<JudgeResult> converter = new BeanOutputConverter<>(JudgeResult.class);

            // 2. 格式化搜索结果为可读文本
            String formattedSearchResults = formatSearchResults(request.searchResults());
            int searchResultCount = request.searchResults() != null ? request.searchResults().size() : 0;

            // 3. 填充 Prompt 模板
            String promptContent = new PromptTemplate(ReflectionJudgePrompt.JUDGE_PROMPT)
                    .create(Map.of(
                            "paragraph_title", safe(request.paragraphTitle()),
                            "paragraph_expected", safe(request.paragraphExpected()),
                            "current_summary", safe(request.currentSummary()),
                            "search_results", formattedSearchResults,
                            "reflection_round", String.valueOf(request.reflectionRound() + 1),
                            "max_reflections", String.valueOf(request.maxReflections()),
                            "quality_threshold", String.valueOf(qualityThreshold),
                            "output_schema", converter.getFormat()
                    ))
                    .getContents();

            int promptLength = promptContent.length();
            log.info("[ReflectionService] Prompt构建完成: taskId={}, 搜索结果数={}, Prompt长度={}字符",
                    request.taskId(), searchResultCount, promptLength);

            if (log.isDebugEnabled()) {
                log.debug("[ReflectionService] Prompt内容预览(前500字符): taskId={}, content='{}...'",
                        request.taskId(),
                        promptContent.substring(0, Math.min(500, promptContent.length())));
            }

            // 4. 调用 LLM（通过 TimeContextChatClient 自动添加时间戳上下文）
            long llmStartTime = System.currentTimeMillis();
            String jsonResponse = chatClient
                    .call(new Prompt(promptContent))
                    .getResult()
                    .getOutput()
                    .getContent();
            long llmDuration = System.currentTimeMillis() - llmStartTime;

            log.info("[ReflectionService] LLM调用完成: taskId={}, LLM耗时={}ms, 响应长度={}字符",
                    request.taskId(), llmDuration,
                    jsonResponse != null ? jsonResponse.length() : 0);

            if (jsonResponse == null || jsonResponse.isBlank()) {
                log.warn("[ReflectionService] LLM返回空响应，采用默认继续策略: taskId={}", request.taskId());
                return defaultContinueResponse(request);
            }

            if (log.isDebugEnabled()) {
                log.debug("[ReflectionService] LLM原始响应: taskId={}, response='{}'",
                        request.taskId(),
                        jsonResponse.substring(0, Math.min(1000, jsonResponse.length())));
            }

            // 5. 解析 LLM 输出
            JudgeResult judgeResult = parseJudgeResult(converter, jsonResponse, request);

            // 6. 强制早停条件检查（LLM 可能未遵守约束）
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
            log.info("[ReflectionService] 评判完成: taskId={}, 总分={:.2f}, 覆盖={:.1f}, 深度={:.1f}, " +
                            "多样性={:.1f}, 时效={:.1f}, 密度={:.1f}, 早停={}, 原因={}, 总耗时={}ms",
                    request.taskId(),
                    judgeResult.qualityScore(),
                    judgeResult.coverageScore(), judgeResult.depthScore(),
                    judgeResult.diversityScore(), judgeResult.recencyScore(),
                    judgeResult.densityScore(),
                    !shouldContinue,
                    stopReason != null ? stopReason : "继续搜索",
                    totalDuration);

            if (shouldContinue && judgeResult.nextSearchFocus() != null) {
                log.info("[ReflectionService] 下一轮搜索方向: taskId={}, focus='{}'",
                        request.taskId(), judgeResult.nextSearchFocus());
            }

            if (judgeResult.identifiedGaps() != null && !judgeResult.identifiedGaps().isEmpty()) {
                log.info("[ReflectionService] 识别的知识缺口: taskId={}, gaps={}",
                        request.taskId(), judgeResult.identifiedGaps());
            }

            return new ReflectionResponse(
                    shouldContinue,
                    judgeResult.qualityScore(),
                    judgeResult.identifiedGaps() != null ? judgeResult.identifiedGaps() : List.of(),
                    judgeResult.nextSearchFocus(),
                    judgeResult.reflectionSummary()
            );

        } catch (org.springframework.web.client.RestClientException e) {
            // API 调用失败（认证错误、超时等），降级处理
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("[ReflectionService] LLM API 调用失败，降级为默认策略: taskId={}, 总耗时={}ms, 错误={}",
                    request.taskId(), totalDuration, e.getMessage());
            return defaultContinueResponse(request);
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("[ReflectionService] 评判异常: taskId={}, 总耗时={}ms, 错误={}",
                    request.taskId(), totalDuration, e.getMessage(), e);
            // 容错：任何异常不影响主流程，默认继续搜索
            return defaultContinueResponse(request);
        }
    }

    // 搜索结果截断配置
    private static final int MAX_SEARCH_RESULTS = 10;      // 最多取前 10 条
    private static final int MAX_CONTENT_LENGTH = 200;     // 每条内容最多 200 字符

    /**
     * 将搜索结果格式化为 Prompt 可读文本。
     * <p>限制数量和长度避免超出 token 限制。
     */
    private String formatSearchResults(List<SourceItem> results) {
        if (results == null || results.isEmpty()) {
            log.debug("[ReflectionService] 搜索结果为空");
            return "（本轮无搜索结果）";
        }

        int originalSize = results.size();
        int limit = Math.min(originalSize, MAX_SEARCH_RESULTS);
        log.info("[ReflectionService] 格式化搜索结果: 原始{}条, 取前{}条", originalSize, limit);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            SourceItem item = results.get(i);
            sb.append(i + 1).append(". ");
            sb.append("[").append(item.source() != null ? item.source() : "Unknown").append("] ");
            if (item.title() != null) sb.append("**").append(item.title()).append("**\n");
            if (item.score() != null) sb.append("   相关度: ").append(String.format("%.2f", item.score())).append("\n");
            if (item.content() != null) {
                String content = item.content();
                if (content.length() > MAX_CONTENT_LENGTH) {
                    content = content.substring(0, MAX_CONTENT_LENGTH) + "...";
                }
                sb.append("   摘要: ").append(content).append("\n");
            }
            sb.append("\n");
        }

        if (originalSize > MAX_SEARCH_RESULTS) {
            sb.append("... (还有 ").append(originalSize - MAX_SEARCH_RESULTS).append(" 条结果已省略)\n");
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 输出，带 fallback 降级：
     * 先用 BeanOutputConverter，失败则尝试 JSON 手动解析，最终降级为默认继续策略。
     */
    private JudgeResult parseJudgeResult(BeanOutputConverter<JudgeResult> converter,
                                          String jsonResponse,
                                          ReflectionRequest request) {
        // 尝试主路径解析
        try {
            JudgeResult result = converter.convert(jsonResponse);
            log.debug("[ReflectionService] BeanOutputConverter解析成功: taskId={}", request.taskId());
            return result;
        } catch (Exception e) {
            log.warn("[ReflectionService] BeanOutputConverter解析失败，尝试手动解析: taskId={}, error={}",
                    request.taskId(), e.getMessage());
        }

        // 尝试手动 JSON 解析
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
            log.info("[ReflectionService] 手动JSON解析成功: taskId={}, qualityScore={}",
                    request.taskId(), qs);
            return new JudgeResult(qs, sc, gaps, focus, summary, 0, 0, 0, 0, 0);
        } catch (Exception ex) {
            log.error("[ReflectionService] 手动JSON解析也失败: taskId={}, error={}",
                    request.taskId(), ex.getMessage());
            // 最终降级：保守的默认值
            return new JudgeResult(0.5, true, List.of(), "", "解析失败，采用默认策略", 0, 0, 0, 0, 0);
        }
    }

    /**
     * 异常或空响应时的默认"继续搜索"响应（容错降级）。
     */
    private ReflectionResponse defaultContinueResponse(ReflectionRequest request) {
        boolean shouldContinue = request.reflectionRound() < request.maxReflections() - 1;
        log.warn("[ReflectionService] 使用默认降级响应: taskId={}, shouldContinue={}, qualityScore=0.5",
                request.taskId(), shouldContinue);
        return new ReflectionResponse(
                shouldContinue,
                0.5,
                List.of(),
                null,
                "Reflection 服务异常，采用默认策略"
        );
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
