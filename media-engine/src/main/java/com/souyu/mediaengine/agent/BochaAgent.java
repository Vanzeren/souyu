package com.souyu.mediaengine.agent;

import com.souyu.common.agent.AbstractAgent;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.bocha.BochaClient;
import com.souyu.common.bocha.model.BochaResponse;
import com.souyu.common.bocha.model.WebResult;
import com.souyu.common.prompt.BochaPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BochaAgent extends AbstractAgent<BochaResponse, Object> {

    private static final Logger logger = LoggerFactory.getLogger(BochaAgent.class);

    @Autowired
    private BochaClient bochaClient;

    @Override
    protected AgentConfig getAgentConfig() {
        // TODO: 注入 Bocha 专用配置，目前使用默认配置
        return new AgentConfig();
    }

    @Override
    public String engineName(){return "media";}

    @Override
    public BochaResponse executeSearchTool(String toolName, String query, Map<String, Object> kwargs) {
        logger.info("  → Bocha 执行搜索工具: {}", toolName);
        
        if (toolName == null) {
            logger.warn("  ⚠️  工具名称为空，使用默认综合搜索");
            return bochaClient.comprehensiveSearch(query, 10);
        }

        switch (toolName) {
            case "comprehensive_search":
                int maxResultsComp = 10;
                if (kwargs != null && kwargs.containsKey("max_results")) {
                    try {
                        maxResultsComp = Integer.parseInt(kwargs.get("max_results").toString());
                    } catch (NumberFormatException e) {
                        logger.warn("max_results 参数格式错误，使用默认值 10");
                    }
                }
                return bochaClient.comprehensiveSearch(query, maxResultsComp);

            case "web_search_only":
                int maxResultsWeb = 15;
                if (kwargs != null && kwargs.containsKey("max_results")) {
                    try {
                        maxResultsWeb = Integer.parseInt(kwargs.get("max_results").toString());
                    } catch (NumberFormatException e) {
                        logger.warn("max_results 参数格式错误，使用默认值 15");
                    }
                }
                return bochaClient.webSearchOnly(query, maxResultsWeb);

            case "search_for_structured_data":
                return bochaClient.searchForStructuredData(query);

            case "search_last_24_hours":
                return bochaClient.searchLast24Hours(query);

            case "search_last_week":
                return bochaClient.searchLastWeek(query);

            default:
                logger.warn("  ⚠️  未知的搜索工具: {}，使用默认综合搜索", toolName);
                return bochaClient.comprehensiveSearch(query, 10);
        }
    }

    @Override
    protected List<Map<String, Object>> extractSearchResults(BochaResponse response) {
        List<Map<String, Object>> results = new ArrayList<>();

        if (response == null || response.getWebpages() == null) {
            return results;
        }

        for (WebResult webResult : response.getWebpages()) {
            Map<String, Object> map = new HashMap<>();

            // 字段映射：将 Bocha 的 WebResult 映射到 AbstractAgent 期望的标准字段
            map.put("title", webResult.getName());
            map.put("url", webResult.getUrl());
            map.put("content", webResult.getSnippet());
            map.put("published_date", webResult.getDatePublished());

            // Bocha 特有字段也可以保留，供后续可能使用
            map.put("site_name", webResult.getSiteName());
            map.put("site_icon", webResult.getSiteIcon());

            results.add(map);
        }

        return results;
    }

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
    @Override
    protected Map<String,String> getPrompts(){
        HashMap<String,String> prompts = new HashMap<>();
        prompts.put("report_structure", BochaPrompts.SYSTEM_PROMPT_REPORT_STRUCTURE);
        prompts.put("first_search",BochaPrompts.SYSTEM_PROMPT_FIRST_SEARCH);
        prompts.put("first_summary",BochaPrompts.SYSTEM_PROMPT_FIRST_SUMMARY);
        prompts.put("reflection",BochaPrompts.SYSTEM_PROMPT_REFLECTION);
        prompts.put("reflection_summary",BochaPrompts.SYSTEM_PROMPT_REFLECTION_SUMMARY);
        prompts.put("report_formatting",BochaPrompts.SYSTEM_PROMPT_REPORT_FORMATTING);
        return prompts;
    }
}
