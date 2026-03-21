package com.souyu.common.dto.reflection;

import java.util.List;

/**
 * Reflection Agent 的响应体。
 * reflection-engine 对段落内容进行 LLM-as-Judge 评估后，将结果同步返回给调用方。
 *
 * @param shouldContinue      是否需要继续搜索（false = 早停）
 * @param qualityScore        内容综合质量分，范围 [0.0, 1.0]，≥ 0.75 触发早停
 * @param identifiedGaps      当前段落中识别到的主要知识缺口列表
 * @param nextSearchFocus     下一轮搜索的方向描述，以补充约束形式注入原有 Prompt（不替换搜索词）
 * @param reflectionSummary   反思结论摘要（用于日志记录和状态跟踪）
 */
public record ReflectionResponse(
        boolean shouldContinue,
        double qualityScore,
        List<String> identifiedGaps,
        String nextSearchFocus,
        String reflectionSummary
) {}
