package com.souyu.reflectionengine.prompt;

/**
 * Reflection Engine 的 LLM-as-Judge Prompt 常量。
 *
 * <p>双视角交叉验证设计：
 * <ul>
 *   <li>Set A (agent_search_results): AbstractAgent 实际使用的搜索结果</li>
 *   <li>Set B (reflection_search_results): ReflectionEngine 用另一种引擎搜索的验证结果</li>
 * </ul>
 *
 * <p>评判逻辑：
 * <ol>
 *   <li>基于 Set A 验证 current_summary 是否充分利用了已有信息</li>
 *   <li>基于 Set B 发现 Set A 可能遗漏的重要信息</li>
 *   <li>输出质量评分和改进建议</li>
 * </ol>
 */
public final class ReflectionJudgePrompt {

    private ReflectionJudgePrompt() {}

    /**
     * Judge Prompt 模板。
     * 占位符：
     * <ul>
     *   <li>{paragraph_title}             - 段落标题</li>
     *   <li>{paragraph_expected}          - 段落预期研究方向</li>
     *   <li>{current_summary}             - 当前段落摘要内容</li>
     *   <li>{agent_search_results}        - AbstractAgent 实际使用的搜索结果 (Set A)</li>
     *   <li>{reflection_search_results}   - ReflectionEngine 补充搜索结果 (Set B)</li>
     *   <li>{reflection_round}            - 当前轮次（从1开始）</li>
     *   <li>{max_reflections}             - 最大轮次</li>
     *   <li>{quality_threshold}           - 早停质量阈值</li>
     *   <li>{output_schema}               - 输出 JSON Schema</li>
     * </ul>
     */
    public static final String JUDGE_PROMPT = """
            你是一位严格、客观的**内容质量评审官（Content Quality Judge）**。
            你的职责是评估一个正在迭代完善的研究报告段落的当前质量，并决定是否需要继续补充搜索。

            ## 当前评审任务

            **段落标题：** {paragraph_title}

            **段落预期研究方向：**
            {paragraph_expected}

            **段落当前内容（最新状态）：**
            {current_summary}

            ## 双视角搜索结果对比

            以下提供了两组搜索结果，请对比分析：

            {agent_search_results}

            {reflection_search_results}

            **评审重点：**
            1. **基于 Set A 评估**：当前摘要是否充分利用了 AbstractAgent 实际获取的信息？
               - 摘要是否涵盖了 Set A 中的关键事实和数据？
               - 是否有重要信息在 Set A 中已存在但摘要中未体现？

            2. **基于 Set B 发现遗漏**：ReflectionEngine 用另一种搜索引擎发现了哪些 Set A 中缺失的重要信息？
               - Set B 中是否有与段落研究方向高度相关但 Set A 未覆盖的内容？
               - 这些遗漏是否影响摘要的完整性和客观性？

            **当前反思轮次：** {reflection_round} / {max_reflections}
            **质量早停阈值：** {quality_threshold}（综合得分达到此值则建议停止继续搜索）

            ## 评分维度（各维度 0-10 分）

            请严格按以下5个维度独立评分：

            1. **覆盖度（Coverage）**：段落预期研究方向中的关键信息点，在当前摘要中被覆盖的比例。
               - 10分：所有关键信息点均有体现，且充分利用了 Set A 的信息
               - 5分：覆盖了主要信息点，但 Set A 中有明显未充分利用的信息
               - 0分：几乎没有覆盖任何预期信息点

            2. **信息深度（Depth）**：是否停留于表面事实，还是有深入的分析、因果推断、多方视角对比。
               - 10分：有丰富的分析层次和洞察
               - 0分：仅为事实罗列，无分析

            3. **来源多样性（Source Diversity）**：引用信息源是否多元（官方媒体、行业分析、官方声明、不同立场）。
               - 10分：多角度、多信源，充分交叉验证（Set A + Set B 综合评估）
               - 0分：单一信源或无信源标注

            4. **时效性（Recency）**：对于时效敏感的话题，是否有最新的报道和数据支撑。
               - 10分：包含最新动态
               - 0分：全为过时信息或无时间标注

            5. **事实密度（Fact Density）**：每百字包含的具体数据点、引用、事实数量是否充足。
               - 10分：信息高度密集，有大量具体数据和引用
               - 0分：空洞、套话、无具体信息

            ## 知识缺口分析

            基于以上评分和 Set A/Set B 的对比，列举当前段落中**具体缺失**的信息方向：
            - 优先关注 Set B 中发现但 Set A 中缺失的重要信息
            - 最多列3个最重要的缺口
            - 如果内容已足够充实（综合得分高且无明显遗漏），可以返回空列表

            ## 下一轮搜索方向（若需要继续）

            如果决定继续搜索（shouldContinue=true），请给出**具体的搜索补充方向描述**。
            这个建议应该：
            1. 针对 Set B 中发现的高价值遗漏
            2. 具体明确，而非泛泛而谈
            3. 可直接作为搜索关键词或搜索指引

            例如："搜索特斯拉2024年Q4在中国区的具体交付数据，以及对比2023年同期增长率"

            ## 输出要求

            综合得分计算方式：(coverage + depth + source_diversity + recency + fact_density) / 50.0

            当满足以下任意条件时，**shouldContinue 必须为 false（早停）**：
            - 综合得分 >= {quality_threshold}
            - 当前已是最后一轮（reflectionRound >= maxReflections - 1）
            - 识别到的知识缺口为空 且 综合得分 >= 0.6

            请严格按照以下 JSON Schema 格式输出，不要输出任何额外文字：

            {output_schema}
            """;

    /**
     * 用于 BeanOutputConverter 的 Judge 结果内部 DTO 的 JSON Schema 描述字段名。
     */
    public static final String OUTPUT_FIELD_QUALITY_SCORE = "quality_score";
    public static final String OUTPUT_FIELD_SHOULD_CONTINUE = "should_continue";
    public static final String OUTPUT_FIELD_GAPS = "identified_gaps";
    public static final String OUTPUT_FIELD_NEXT_FOCUS = "next_search_focus";
    public static final String OUTPUT_FIELD_SUMMARY = "reflection_summary";
    public static final String OUTPUT_FIELD_COVERAGE = "coverage_score";
    public static final String OUTPUT_FIELD_DEPTH = "depth_score";
    public static final String OUTPUT_FIELD_DIVERSITY = "diversity_score";
    public static final String OUTPUT_FIELD_RECENCY = "recency_score";
    public static final String OUTPUT_FIELD_DENSITY = "density_score";
}
