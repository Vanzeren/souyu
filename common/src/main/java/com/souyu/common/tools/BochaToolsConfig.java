package com.souyu.common.tools;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.souyu.common.bocha.BochaClient;
import com.souyu.common.bocha.model.BochaResponse;
import com.souyu.common.bocha.model.WebResult;
import com.souyu.common.context.SearchContext;
import com.souyu.common.tavily.model.SearchResult;
import com.souyu.common.tavily.model.TavilyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Configuration
public class BochaToolsConfig {

    private static final Logger logger = LoggerFactory.getLogger(BochaToolsConfig.class);

    @Bean
    @Description("全面综合搜索工具，获取网页、图片、AI总结等完整信息")
    public Function<SearchRequest, BochaResponse> comprehensiveSearch(BochaClient bochaClient) {
        return request -> {
            logger.info("Tool called: comprehensiveSearch with query: {}", request.query());
            try {
                BochaResponse response = bochaClient.comprehensiveSearch(request.query(), 10);
                SearchContext.setLastResponse(convertToTavilyResponse(response));
                logger.info("Tool execution successful, results captured in SearchContext");
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("纯网页搜索工具，只获取网页链接和摘要")
    public Function<SearchRequest, BochaResponse> webSearchOnly(BochaClient bochaClient) {
        return request -> {
            logger.info("Tool called: webSearchOnly with query: {}", request.query());
            try {
                BochaResponse response = bochaClient.webSearchOnly(request.query(), 10);
                SearchContext.setLastResponse(convertToTavilyResponse(response));
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("结构化数据查询工具，用于查询天气、股票、汇率等")
    public Function<SearchRequest, BochaResponse> searchForStructuredData(BochaClient bochaClient) {
        return request -> {
            logger.info("Tool called: searchForStructuredData with query: {}", request.query());
            try {
                BochaResponse response = bochaClient.searchForStructuredData(request.query());
                SearchContext.setLastResponse(convertToTavilyResponse(response));
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("24小时内信息搜索工具，获取最新动态")
    public Function<SearchRequest, BochaResponse> searchLast24Hours(BochaClient bochaClient) {
        return request -> {
            logger.info("Tool called: searchLast24Hours with query: {}", request.query());
            try {
                BochaResponse response = bochaClient.searchLast24Hours(request.query());
                SearchContext.setLastResponse(convertToTavilyResponse(response));
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("本周信息搜索工具，获取近期发展趋势")
    public Function<SearchRequest, BochaResponse> searchLastWeek(BochaClient bochaClient) {
        return request -> {
            logger.info("Tool called: searchLastWeek with query: {}", request.query());
            try {
                BochaResponse response = bochaClient.searchLastWeek(request.query());
                SearchContext.setLastResponse(convertToTavilyResponse(response));
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    // 适配器：将 BochaResponse 转换为 TavilyResponse
    private TavilyResponse convertToTavilyResponse(BochaResponse bochaResponse) {
        TavilyResponse tavilyResponse = new TavilyResponse();
        if (bochaResponse == null) return tavilyResponse;

        List<SearchResult> results = new ArrayList<>();
        if (bochaResponse.getWebpages() != null) {
            for (WebResult web : bochaResponse.getWebpages()) {
                SearchResult result = new SearchResult();
                result.setTitle(web.getName());
                result.setUrl(web.getUrl());
                result.setContent(web.getSnippet());
                result.setPublishedDate(web.getDatePublished());
                // Bocha 没有 score 字段，设为默认值或根据 rank 计算
                result.setScore(0.0); 
                results.add(result);
            }
        }
        tavilyResponse.setResults(results);
        
        // 也可以处理 images，如果 TavilyResponse 支持的话
        // ...

        return tavilyResponse;
    }

    @JsonClassDescription("搜索请求")
    public record SearchRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("搜索关键词") String query) {}
}
