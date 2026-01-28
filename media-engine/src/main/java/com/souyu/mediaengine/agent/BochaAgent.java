package com.souyu.mediaengine.agent;

import com.souyu.common.agent.AbstractAgent;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.bocha.BochaClient;
import com.souyu.common.tavily.model.TavilyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BochaAgent extends AbstractAgent<TavilyResponse> {

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

    // 移除 getPrompts()，使用父类统一的 DeepSearchPrompts

    @Override
    protected String[] getToolNames() {
        // 确保这里的名称与 BochaToolsConfig 中的 Bean 名称一致 (驼峰命名)
        return new String[]{
            "comprehensiveSearch",
            "webSearchOnly",
            "searchForStructuredData",
            "searchLast24Hours",
            "searchLastWeek"
        };
    }
    
    // 移除 getToolDescription()

    @Override
    public TavilyResponse executeSearchTool(String toolName, String query, Map<String, Object> kwargs) {
        logger.debug("executeSearchTool called (Legacy path): {}", toolName);
        return null;
    }

    @Override
    protected List<Map<String, Object>> extractSearchResults(TavilyResponse response) {
        // 注意：这里的 response 实际上是 BochaToolsConfig 中转换后的 TavilyResponse
        if (response == null || response.getResults() == null) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> resultsMap = new ArrayList<>();
        for (var result : response.getResults()) {
            Map<String, Object> map = new HashMap<>();
            map.put("title", result.getTitle());
            map.put("url", result.getUrl());
            map.put("content", result.getContent());
            map.put("published_date", result.getPublishedDate());
            resultsMap.add(map);
        }
        return resultsMap;
    }
}
