package com.souyu.common.dto.reflection;

import com.souyu.common.dto.SourceItem;
import java.util.List;

/**
 * Reflection Agent 的请求体。
 * 包含当前段落的完整上下文，由 query-engine / media-engine 在每轮反思前发送给 reflection-engine。
 *
 * <p>Reflection Engine 的评判逻辑：
 * <ol>
 *   <li>基于 {@link #paragraphTitle} 和 {@link #paragraphExpected} 用<strong>另一种搜索引擎</strong>独立搜索</li>
 *   <li>对比 {@link #agentSearchResults} (AbstractAgent 实际使用的搜索结果) 和自身搜索结果</li>
 *   <li>评判 {@link #currentSummary} 是否充分利用了已有信息，以及是否有重要遗漏</li>
 *   <li>输出改进建议和下一轮的搜索方向</li>
 * </ol>
 *
 * @param taskId             任务 ID
 * @param paragraphTitle     段落标题
 * @param paragraphExpected  段落预期研究方向（来自报告结构规划阶段）
 * @param currentSummary     段落当前最新摘要内容（基于 agentSearchResults 生成）
 * @param agentSearchResults AbstractAgent 本轮实际使用的搜索结果（来自 Tavily/Bocha）
 * @param reflectionRound    当前是第几轮反思（从 0 开始）
 * @param maxReflections     最大反思轮数
 * @param engineName         来源引擎名称（"query" / "media"）
 * @param agentSearchEngine  AbstractAgent 使用的搜索引擎（"tavily" 或 "bocha"）
 */
public record ReflectionRequest(
        String taskId,
        String paragraphTitle,
        String paragraphExpected,
        String currentSummary,
        List<SourceItem> agentSearchResults,
        int reflectionRound,
        int maxReflections,
        String engineName,
        String agentSearchEngine
) {
    /**
     * 获取用于独立验证的搜索查询。
     * 基于段落标题和预期内容构造。
     */
    public String toSearchQuery() {
        if (paragraphExpected != null && !paragraphExpected.isBlank()) {
            return paragraphTitle + " " + paragraphExpected;
        }
        return paragraphTitle;
    }

    /**
     * 判断 ReflectionEngine 应该使用哪种搜索引擎进行补充验证。
     * 使用 AbstractAgent <strong>没用过的</strong>那种，保证视角差异。
     */
    public String getReflectionSearchEngine() {
        if ("tavily".equalsIgnoreCase(agentSearchEngine)) {
            return "bocha";
        }
        return "tavily";
    }

    /**
     * 判断是否应该使用 Tavily 进行验证搜索。
     */
    public boolean useTavilyForReflection() {
        return "tavily".equalsIgnoreCase(getReflectionSearchEngine());
    }

    /**
     * 判断是否应该使用 Bocha 进行验证搜索。
     */
    public boolean useBochaForReflection() {
        return "bocha".equalsIgnoreCase(getReflectionSearchEngine());
    }
}
