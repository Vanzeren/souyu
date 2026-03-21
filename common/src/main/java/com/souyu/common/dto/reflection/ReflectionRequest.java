package com.souyu.common.dto.reflection;

import com.souyu.common.dto.SourceItem;
import java.util.List;

/**
 * Reflection Agent 的请求体。
 * 包含当前段落的完整上下文，由 query-engine / media-engine 在每轮反思前发送给 reflection-engine。
 *
 * @param taskId             任务 ID
 * @param paragraphTitle     段落标题
 * @param paragraphExpected  段落预期研究方向（来自报告结构规划阶段）
 * @param currentSummary     段落当前最新摘要内容
 * @param searchResults      本轮搜索捕获的结果列表
 * @param reflectionRound    当前是第几轮反思（从 0 开始）
 * @param maxReflections     最大反思轮数（用于 Judge 参考是否接近结束）
 * @param engineName         来源引擎名称（"query" / "media"）
 */
public record ReflectionRequest(
        String taskId,
        String paragraphTitle,
        String paragraphExpected,
        String currentSummary,
        List<SourceItem> searchResults,
        int reflectionRound,
        int maxReflections,
        String engineName
) {}
