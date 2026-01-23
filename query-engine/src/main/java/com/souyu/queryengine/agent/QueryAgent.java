package com.souyu.queryengine.agent;

import com.souyu.common.agent.AbstractAgent;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.tavily.TavilyClient;
import com.souyu.common.tavily.model.SearchResult;
import com.souyu.common.tavily.model.TavilyResponse;
import com.souyu.queryengine.config.QueryEngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class QueryAgent extends AbstractAgent<TavilyResponse, Object> {

    private static final Logger logger = LoggerFactory.getLogger(QueryAgent.class);

    @Autowired
    private TavilyClient tavilyClient;

    @Autowired
    private QueryEngineConfig queryEngineConfig;

    @Override
    public String engineName(){return "query";}

    @Override
    protected AgentConfig getAgentConfig() {
        // 将 QueryEngineConfig 转换为通用的 AgentConfig
        AgentConfig config = new AgentConfig();
        
        AgentConfig.SearchConfig searchConfig = new AgentConfig.SearchConfig();
        searchConfig.setTimeout(queryEngineConfig.getSearch().getTimeout());
        searchConfig.setContentMaxLength(queryEngineConfig.getSearch().getContentMaxLength());
        searchConfig.setMaxReflections(queryEngineConfig.getSearch().getMaxReflections());
        searchConfig.setMaxParagraphs(queryEngineConfig.getSearch().getMaxParagraphs());
        searchConfig.setMaxResults(queryEngineConfig.getSearch().getMaxResults());
        config.setSearch(searchConfig);

        AgentConfig.OutputConfig outputConfig = new AgentConfig.OutputConfig();
        outputConfig.setDir(queryEngineConfig.getOutput().getDir());
        outputConfig.setSaveIntermediateStates(queryEngineConfig.getOutput().isSaveIntermediateStates());
        config.setOutput(outputConfig);
        
        return config;
    }

    @Override
    public TavilyResponse executeSearchTool(String toolName, String query, Map<String, Object> kwargs) {
        logger.info("  → 执行搜索工具: {}", toolName);

        if (toolName == null) {
            logger.warn("  ⚠️  工具名称为空，使用默认基础搜索");
            return tavilyClient.basicSearchNews(query);
        }

        switch (toolName) {
            case "basic_search_news":
                int maxResults = 7;
                if (kwargs != null && kwargs.containsKey("max_results")) {
                    try {
                        maxResults = Integer.parseInt(kwargs.get("max_results").toString());
                    } catch (NumberFormatException e) {
                        logger.warn("max_results 参数格式错误，使用默认值 7");
                    }
                }
                return tavilyClient.basicSearchNews(query, maxResults);

            case "deep_search_news":
                return tavilyClient.deepSearchNews(query);

            case "search_news_last_24_hours":
                return tavilyClient.searchNewsLast24Hours(query);

            case "search_news_last_week":
                return tavilyClient.searchNewsLastWeek(query);

            case "search_images_for_news":
                return tavilyClient.searchImagesForNews(query);

            case "search_news_by_date":
                String startDate = kwargs != null ? (String) kwargs.get("start_date") : null;
                String endDate = kwargs != null ? (String) kwargs.get("end_date") : null;

                if (startDate == null || endDate == null) {
                    throw new IllegalArgumentException("search_news_by_date工具需要start_date和end_date参数");
                }

                if (!validateDateFormat(startDate) || !validateDateFormat(endDate)) {
                    throw new IllegalArgumentException("日期格式错误，应为 YYYY-MM-DD");
                }

                return tavilyClient.searchNewsByDate(query, startDate, endDate);

            default:
                logger.warn("  ⚠️  未知的搜索工具: {}，使用默认基础搜索", toolName);
                return tavilyClient.basicSearchNews(query);
        }
    }

    @Override
    protected List<Map<String, Object>> extractSearchResults(TavilyResponse response) {
        if (response == null || response.getResults() == null) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> resultsMap = new ArrayList<>();
        for (SearchResult result : response.getResults()) {
            Map<String, Object> map = new HashMap<>();
            map.put("title", result.getTitle());
            map.put("url", result.getUrl());
            map.put("content", result.getContent());
            map.put("score", result.getScore());
            map.put("raw_content", result.getRawContent());
            map.put("published_date", result.getPublishedDate());
            resultsMap.add(map);
        }
        return resultsMap;
    }
}