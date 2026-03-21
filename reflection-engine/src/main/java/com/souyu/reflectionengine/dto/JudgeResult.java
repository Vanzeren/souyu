package com.souyu.reflectionengine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * LLM Judge 输出的内部中间 DTO（用于 BeanOutputConverter 解析）。
 * 包含各维度独立分数，便于调试和日志记录。
 */
public record JudgeResult(
        @JsonProperty("quality_score")    double qualityScore,
        @JsonProperty("should_continue")  boolean shouldContinue,
        @JsonProperty("identified_gaps")  List<String> identifiedGaps,
        @JsonProperty("next_search_focus") String nextSearchFocus,
        @JsonProperty("reflection_summary") String reflectionSummary,
        @JsonProperty("coverage_score")   double coverageScore,
        @JsonProperty("depth_score")      double depthScore,
        @JsonProperty("diversity_score")  double diversityScore,
        @JsonProperty("recency_score")    double recencyScore,
        @JsonProperty("density_score")    double densityScore
) {}
