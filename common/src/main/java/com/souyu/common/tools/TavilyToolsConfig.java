package com.souyu.common.tools;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.souyu.common.context.SearchContext;
import com.souyu.common.tavily.TavilyClient;
import com.souyu.common.tavily.model.TavilyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
public class TavilyToolsConfig {

    private static final Logger logger = LoggerFactory.getLogger(TavilyToolsConfig.class);

    @Bean
    @Description("基础新闻搜索工具。适用于：一般性的新闻搜索，不确定需要何种特定搜索时。特点：快速、标准的通用搜索，是最常用的基础工具")
    public Function<BasicSearchRequest, TavilyResponse> basicSearchNews(TavilyClient tavilyClient) {
        return request -> {
            logger.info("Tool called: basicSearchNews with query: {}", request.query());
            try {
                TavilyResponse response = tavilyClient.basicSearchNews(request.query(), 7);
                SearchContext.setLastResponse(response);
                logger.info("Tool execution successful, results captured in SearchContext");
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null; 
            }
        };
    }

    @Bean
    @Description("深度新闻分析工具。适用于：需要全面深入了解某个主题时。特点：提供最详细的分析结果，包含高级AI摘要")
    public Function<BasicSearchRequest, TavilyResponse> deepSearchNews(TavilyClient tavilyClient) {
        return request -> {
            logger.info("Tool called: deepSearchNews with query: {}", request.query());
            try {
                TavilyResponse response = tavilyClient.deepSearchNews(request.query());
                SearchContext.setLastResponse(response);
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("24小时最新新闻工具。适用于：需要了解最新动态、突发事件时。特点：只搜索过去24小时的新闻")
    public Function<BasicSearchRequest, TavilyResponse> searchNewsLast24Hours(TavilyClient tavilyClient) {
        return request -> {
            logger.info("Tool called: searchNewsLast24Hours with query: {}", request.query());
            try {
                TavilyResponse response = tavilyClient.searchNewsLast24Hours(request.query());
                SearchContext.setLastResponse(response);
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("本周新闻工具。适用于：需要了解近期发展趋势时。特点：搜索过去一周的新闻报道")
    public Function<BasicSearchRequest, TavilyResponse> searchNewsLastWeek(TavilyClient tavilyClient) {
        return request -> {
            logger.info("Tool called: searchNewsLastWeek with query: {}", request.query());
            try {
                TavilyResponse response = tavilyClient.searchNewsLastWeek(request.query());
                SearchContext.setLastResponse(response);
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("图片搜索工具。适用于：需要可视化信息、图片资料时。特点：提供相关图片和图片描述")
    public Function<BasicSearchRequest, TavilyResponse> searchImagesForNews(TavilyClient tavilyClient) {
        return request -> {
            logger.info("Tool called: searchImagesForNews with query: {}", request.query());
            try {
                TavilyResponse response = tavilyClient.searchImagesForNews(request.query());
                SearchContext.setLastResponse(response);
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    @Bean
    @Description("按日期范围搜索工具。适用于：需要研究特定历史时期时。特点：可以指定开始和结束日期进行搜索。特殊要求：必须提供start_date和end_date参数，格式为'YYYY-MM-DD'。注意：只有这个工具需要额外的时间参数")
    public Function<DateRangeSearchRequest, TavilyResponse> searchNewsByDate(TavilyClient tavilyClient) {
        return request -> {
            logger.info("Tool called: searchNewsByDate with query: {}, start: {}, end: {}", request.query(), request.startDate(), request.endDate());
            try {
                if (request.startDate() == null || request.endDate() == null) {
                    throw new IllegalArgumentException("start_date and end_date are required.");
                }
                TavilyResponse response = tavilyClient.searchNewsByDate(request.query(), request.startDate(), request.endDate());
                SearchContext.setLastResponse(response);
                return response;
            } catch (Exception e) {
                logger.error("Tool execution failed", e);
                return null;
            }
        };
    }

    // 定义请求对象
    
    @JsonClassDescription("基础搜索请求")
    public record BasicSearchRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("搜索关键词") String query) {}

    @JsonClassDescription("日期范围搜索请求")
    public record DateRangeSearchRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("搜索关键词") String query,
            @JsonProperty(required = true) @JsonPropertyDescription("开始日期，格式YYYY-MM-DD") String startDate,
            @JsonProperty(required = true) @JsonPropertyDescription("结束日期，格式YYYY-MM-DD") String endDate) {}
}
