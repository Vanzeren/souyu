package com.souyu.common.tavily;

import com.souyu.common.tavily.model.SearchResult;
import com.souyu.common.tavily.model.TavilyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tavily 真实接口调用测试
 * 注意：运行此测试需要真实的 API Key 和网络连接
 */
public class TavilyIntegrationTest {

    private TavilyClient tavilyClient;

    @BeforeEach
    void setUp() {
        // 尝试从环境变量获取 Key，如果没有则使用默认值（请替换为你自己的 Key）
        String apiKey = System.getenv("TAVILY_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            // TODO: 如果环境变量未设置，请在这里填入你的真实 Key
            apiKey = "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";
        }

        // 使用真实的 RestTemplate
        RestTemplateBuilder builder = new RestTemplateBuilder();
        tavilyClient = new TavilyClient(builder, apiKey);
    }

    @Test
    void testRealSearch() {
        String query = "2024年人工智能最新突破";
        System.out.println("正在搜索: " + query + " ...");

        try {
            TavilyResponse response = tavilyClient.basicSearchNews(query, 5);

            System.out.println("\n================= 搜索结果 =================");
            System.out.println("查询词: " + response.getQuery());
            System.out.println("耗时: " + response.getResponseTime() + "s");
            
            List<SearchResult> results = response.getResults();
            if (results != null) {
                System.out.println("找到 " + results.size() + " 条结果:\n");
                for (int i = 0; i < results.size(); i++) {
                    SearchResult result = results.get(i);
                    System.out.println("[" + (i + 1) + "] " + result.getTitle());
                    System.out.println("    发布日期: " + result.getPublishedDate());
                    System.out.println("    链接: " + result.getUrl());
                    System.out.println("    摘要: " + (result.getContent() != null && result.getContent().length() > 100 ? result.getContent().substring(0, 100) + "..." : result.getContent()));
                    System.out.println();
                }
            } else {
                System.out.println("未找到结果。");
            }
            System.out.println("============================================");

            assertNotNull(response.getResults());

        } catch (Exception e) {
            System.err.println("搜索失败: " + e.getMessage());
            // 如果是因为 Key 无效，提醒用户
            if (e.getMessage().contains("401") || e.getMessage().contains("403")) {
                System.err.println("请检查你的 API Key 是否正确！");
            }
            throw e;
        }
    }
}
