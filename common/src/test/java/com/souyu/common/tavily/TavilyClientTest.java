package com.souyu.common.tavily;

import com.souyu.common.tavily.model.SearchResult;
import com.souyu.common.tavily.model.TavilyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TavilyClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    private TavilyClient tavilyClient;

    @BeforeEach
    void setUp() {
        System.out.println("Setting up test...");
        MockitoAnnotations.openMocks(this);
        
        // Mock builder.build() to return our mock restTemplate
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        
        // Inject mocks via constructor
        tavilyClient = new TavilyClient(restTemplateBuilder, "test-api-key");
        System.out.println("Setup complete.");
    }

    @Test
    void testBasicSearchNews() {
        System.out.println("Running testBasicSearchNews...");
        try {
            // Arrange
            String query = "奥运会";
            TavilyResponse mockResponse = new TavilyResponse();
            mockResponse.setQuery(query);
            SearchResult result = new SearchResult();
            result.setTitle("奥运会最新消息");
            result.setUrl("https://example.com/olympics");
            result.setContent("这里是关于奥运会的最新报道...");
            result.setScore(0.95);
            mockResponse.setResults(Collections.singletonList(result));

            when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(TavilyResponse.class)))
                    .thenReturn(mockResponse);

            // Act
            TavilyResponse response = tavilyClient.basicSearchNews(query);

            // Print Result
            System.out.println("--------------------------------------------------");
            System.out.println("Search Result:");
            System.out.println("Query: " + response.getQuery());
            if (response.getResults() != null) {
                for (SearchResult r : response.getResults()) {
                    System.out.println("Title: " + r.getTitle());
                    System.out.println("URL: " + r.getUrl());
                    System.out.println("Content: " + r.getContent());
                    System.out.println("Score: " + r.getScore());
                }
            }
            System.out.println("--------------------------------------------------");

            // Assert
            assertNotNull(response, "Response should not be null");
            assertEquals(query, response.getQuery(), "Query should match");
            assertEquals("奥运会最新消息", response.getResults().get(0).getTitle(), "Title should match");

            // Verify parameters
            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForObject(eq("https://api.tavily.com/search"), entityCaptor.capture(), eq(TavilyResponse.class));
            
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertEquals("news", body.get("topic"));
            assertEquals("basic", body.get("search_depth"));
            assertEquals("test-api-key", body.get("api_key"));
            
            System.out.println("testBasicSearchNews PASSED");
        } catch (Exception e) {
            System.err.println("testBasicSearchNews FAILED");
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    void testDeepSearchNews() {
        System.out.println("Running testDeepSearchNews...");
        try {
            // Arrange
            String query = "AI技术";
            TavilyResponse mockResponse = new TavilyResponse();
            mockResponse.setAnswer("AI技术正在快速发展...");
            
            when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(TavilyResponse.class)))
                    .thenReturn(mockResponse);

            // Act
            tavilyClient.deepSearchNews(query);

            // Verify parameters
            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForObject(any(String.class), entityCaptor.capture(), eq(TavilyResponse.class));
            
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertEquals("advanced", body.get("search_depth"));
            assertEquals("advanced", body.get("include_answer"));
            assertEquals(20, body.get("max_results"));
            
            System.out.println("testDeepSearchNews PASSED");
        } catch (Exception e) {
            System.err.println("testDeepSearchNews FAILED");
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    void testSearchNewsByDate() {
        System.out.println("Running testSearchNewsByDate...");
        try {
            // Arrange
            String query = "历史事件";
            String startDate = "2023-01-01";
            String endDate = "2023-12-31";
            
            when(restTemplate.postForObject(any(String.class), any(HttpEntity.class), eq(TavilyResponse.class)))
                    .thenReturn(new TavilyResponse());

            // Act
            tavilyClient.searchNewsByDate(query, startDate, endDate);

            // Verify parameters
            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).postForObject(any(String.class), entityCaptor.capture(), eq(TavilyResponse.class));
            
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertEquals(startDate, body.get("start_date"));
            assertEquals(endDate, body.get("end_date"));
            
            System.out.println("testSearchNewsByDate PASSED");
        } catch (Exception e) {
            System.err.println("testSearchNewsByDate FAILED");
            e.printStackTrace();
            throw e;
        }
    }
}
