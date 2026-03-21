package com.souyu.reflectionengine.prompt;

/**
 * Reflection Engine 的 LLM-as-Judge Prompt 常量。
 *
 * <p>与 common 模块中的 {@code DeepSearchPrompts.SYSTEM_PROMPT_REFLECTION} 有本质区别：
 * <ul>
 *   <li>现有 Prompt 是"搜索规划者"——决定下一个搜索词</li>
 *   <li>本 Prompt 是"内容质量评判者"——基于明确维度评分并决定是否继续</li>
 * </ul>
 */
public final class ReflectionJudgePrompt {

    private ReflectionJudgePrompt() {}

    /**
     * Judge Prompt 模板。
     * 占位符：
     * <ul>
     *   <li>{paragraph_title}      - 段落标题</li>
     *   <li>{paragraph_expected}   - 段落预期研究方向</li>
     *   <li>{current_summary}      - 当前段落摘要内容</li>
     *   <li>{search_results}       - 本轮搜索结果（格式化后的文本）</li>
     *   <li>{reflection_round}     - 当前轮次（从1开始）</li>
     *   <li>{max_reflections}      - 最大轮次</li>
     *   <li>{quality_threshold}    - 早停质量阈值</li>
     *   <li>{output_schema}        - 输出 JSON Schema（由 BeanOutputConverter 注入）</li>
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
            
            **本轮新增搜索结果：**
            {search_results}
            
            **当前反思轮次：** {reflection_round} / {max_reflections}
            **质量早停阈值：** {quality_threshold}（综合得分达到此值则建议停止继续搜索）
            
            ## 评分维度（各维度 0-10 分）
            
            请严格按以下5个维度独立评分：
            
            1. **覆盖度（Coverage）**：段落预期研究方向中的关键信息点，在当前摘要中被覆盖的比例。
               - 10分：所有关键信息点均有体现
               - 0分：几乎没有覆盖任何预期信息点
            
            2. **信息深度（Depth）**：是否停留于表面事实，还是有深入的分析、因果推断、多方视角对比。
               - 10分：有丰富的分析层次和洞察
               - 0分：仅为事实罗列，无分析
            
            3. **来源多样性（Source Diversity）**：引用信息源是否多元（官方媒体、行业分析、官方声明、不同立场）。
               - 10分：多角度、多信源，充分交叉验证
               - 0分：单一信源或无信源标注
            
            4. **时效性（Recency）**：对于时效敏感的话题，是否有最新的报道和数据支撑。
               - 10分：包含最新动态
               - 0分：全为过时信息或无时间标注
            
            5. **事实密度（Fact Density）**：每百字包含的具体数据点、引用、事实数量是否充足。
               - 10分：信息高度密集，有大量具体数据和引用
               - 0分：空洞、套话、无具体信息
            
            ## 知识缺口分析
            
            基于以上评分，列举当前段落中**具体缺失**的信息方向（而非泛泛而谈）。
            最多列3个最重要的缺口。如果内容已足够充实，可以返回空列表。
            
            ## 下一轮搜索方向（若需要继续）
            
            如果决定继续搜索（shouldContinue=true），请给出**具体的搜索补充方向描述**。
            这将以"补充约束"的方式注入到现有搜索 Prompt 中，引导 LLM 在原有 Function Calling 机制下调整搜索侧重点。
            例如："请重点搜索该事件中官方机构的具体声明和处置措施，以及事件发生后的量化影响数据。"
            
            ## 输出要求
            
            综合得分计算方式：(coverage + depth + source_diversity + recency + fact_density) / 50.0
            
            当满足以下任意条件时，**shouldContinue 必须为 false（早停）**：
            - 综合得分 >= {quality_threshold}
            - 当前已是最后一轮（reflectionRound >= maxReflections - 1）
            - 识别到的知识缺口为空
            
            请严格按照以下 JSON Schema 格式输出，不要输出任何额外文字：
            
            {output_schema}
            """;

    /**
     * 用于 BeanOutputConverter 的 Judge 结果内部 DTO 的 JSON Schema 描述字段名。
     * 实际 Schema 由 BeanOutputConverter 自动生成并注入 {output_schema} 占位符。
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
