package com.souyu.queryengine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.agent.AbstractAgent;
import com.souyu.common.config.AgentConfig;
import com.souyu.common.dto.SourceItem;
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
public class QueryAgent extends AbstractAgent<TavilyResponse> {

    private static final Logger logger = LoggerFactory.getLogger(QueryAgent.class);

    @Autowired
    private TavilyClient tavilyClient;

    @Autowired
    private QueryEngineConfig queryEngineConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // 移除 getPrompts()，使用父类统一的 DeepSearchPrompts

    @Override
    protected String[] getToolNames() {
        return new String[]{
            "basicSearchNews", 
            "deepSearchNews",
            "searchNewsLast24Hours",
            "searchNewsLastWeek",
            "searchImagesForNews",
            "searchNewsByDate"
        };
    }
    
    // 移除 getToolDescription()

    @Override
    public TavilyResponse executeSearchTool(String toolName, String query, Map<String, Object> kwargs) {
        logger.debug("executeSearchTool called (Legacy path): {}", toolName);
        return null; 
    }

    @Override
    protected List<SourceItem> extractSearchResults(TavilyResponse response) {
        if (response == null || response.getResults() == null) {
            return new ArrayList<>();
        }

        List<SourceItem> items = new ArrayList<>();
        for (SearchResult result : response.getResults()) {
            items.add(new SourceItem(
                result.getTitle(),
                result.getUrl(),
                result.getContent(),
                result.getScore(),
                result.getPublishedDate(),
                "Tavily"
            ));
        }
        return items;
    }
}
