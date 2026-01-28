package com.souyu.common.tavily;

import com.souyu.common.tavily.model.TavilyResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class TavilyClient {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private static final String BASE_URL = "https://api.tavily.com/search";

    public TavilyClient(RestTemplateBuilder builder, @Value("${tavily.api-key}") String apiKey) {
        this.restTemplate = builder.build();
        this.apiKey = apiKey;
    }

    // 提供一个受保护的构造函数供测试使用，或者直接在测试中 mock RestTemplateBuilder
    // 这里我们选择在测试中 mock RestTemplateBuilder，因为它更符合 Spring Boot 的风格

    private TavilyResponse searchInternal(Map<String, Object> params) {
        params.put("api_key", apiKey);
        if (!params.containsKey("topic")) {
            params.put("topic", "general");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(params, headers);

        return restTemplate.postForObject(BASE_URL, request, TavilyResponse.class);
    }

    /**
     * 基础新闻搜索: 执行一次标准、快速的新闻搜索。
     */
    public TavilyResponse basicSearchNews(String query, int maxResults) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("max_results", maxResults);
        params.put("topic", "news");
        params.put("search_depth", "basic");
        params.put("include_answer", false);
        return searchInternal(params);
    }

    /**
     * 深度新闻分析: 对一个主题进行最全面、最深入的搜索。
     */
    public TavilyResponse deepSearchNews(String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("topic", "news");
        params.put("search_depth", "advanced");
        params.put("max_results", 20);
        params.put("include_answer", "advanced");
        return searchInternal(params);
    }

    /**
     * 搜索24小时内新闻: 获取关于某个主题的最新动态。
     */
    public TavilyResponse searchNewsLast24Hours(String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("topic", "news");
        params.put("time_range", "d");
        params.put("max_results", 10);
        return searchInternal(params);
    }

    /**
     * 搜索本周新闻: 获取关于某个主题过去一周内的主要新闻报道。
     */
    public TavilyResponse searchNewsLastWeek(String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("topic", "news");
        params.put("time_range", "w");
        params.put("max_results", 10);
        return searchInternal(params);
    }

    /**
     * 查找新闻图片: 搜索与某个新闻主题相关的图片。
     */
    public TavilyResponse searchImagesForNews(String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("topic", "news");
        params.put("include_images", true);
        params.put("include_image_descriptions", true);
        params.put("max_results", 5);
        return searchInternal(params);
    }

    /**
     * 按指定日期范围搜索新闻: 在一个明确的历史时间段内搜索新闻。
     * 日期格式: YYYY-MM-DD
     */
    public TavilyResponse searchNewsByDate(String query, String startDate, String endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("topic", "news");
        params.put("start_date", startDate);
        params.put("end_date", endDate);
        params.put("max_results", 15);
        return searchInternal(params);
    }
}
