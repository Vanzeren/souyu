package com.souyu.common.bocha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.souyu.common.bocha.model.BochaResponse;
import com.souyu.common.bocha.model.ImageResult;
import com.souyu.common.bocha.model.WebResult;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class BochaClient {
    private static final Logger logger = LoggerFactory.getLogger(BochaClient.class);
    private static final String API_URL = "https://api.bochaai.com/v1/ai-search";

    @Value("${bocha.api-key}")
    private String apiKey;

    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public BochaClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        this.mapper = new ObjectMapper();
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 内部通用搜索方法
     *
     * @param query     搜索查询
     * @param freshness 搜索时间范围 (noLimit, oneDay, oneWeek, oneMonth, oneYear)
     * @param count     返回结果数量
     * @param answer    是否生成AI回答
     * @return BochaResponse
     */
    private BochaResponse searchInternal(String query, String freshness, int count, boolean answer) {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("query", query);
        payloadMap.put("freshness", freshness);
        payloadMap.put("answer", answer);
        payloadMap.put("stream", false);
        payloadMap.put("count", count);

        String jsonPayload;
        try {
            jsonPayload = mapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize payload", e);
            return new BochaResponse();
        }

        RequestBody body = RequestBody.create(jsonPayload, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Bocha API request failed: code={}, body={}", response.code(), response.body() != null ? response.body().string() : "null");
                return new BochaResponse();
            }

            if (response.body() == null) {
                return new BochaResponse();
            }

            String responseBody = response.body().string();
            return parseResponse(responseBody);

        } catch (IOException e) {
            logger.error("Bocha API call failed", e);
            return new BochaResponse();
        }
    }

    /**
     * 【工具】全面综合搜索: 获取网页、图片、AI总结等完整信息。
     * 适用于一般性的研究需求。
     */
    public BochaResponse comprehensiveSearch(String query, int maxResults) {
        logger.info("--- TOOL: 全面综合搜索 (query: {}) ---", query);
        return searchInternal(query, "noLimit", maxResults, true);
    }

    /**
     * 【工具】纯网页搜索: 只获取网页链接和摘要，不请求AI生成答案。
     * 适用于需要快速获取原始网页信息，而不需要AI额外分析的场景。速度更快，成本更低。
     */
    public BochaResponse webSearchOnly(String query, int maxResults) {
        logger.info("--- TOOL: 纯网页搜索 (query: {}) ---", query);
        return searchInternal(query, "noLimit", maxResults, false);
    }

    /**
     * 【工具】结构化数据查询: 专门用于可能触发“模态卡”的查询。
     * 当Agent意图是查询天气、股票、汇率、百科定义、火车票、汽车参数等结构化信息时，应优先使用此工具。
     */
    public BochaResponse searchForStructuredData(String query) {
        logger.info("--- TOOL: 结构化数据查询 (query: {}) ---", query);
        // 结构化查询通常不需要太多网页结果
        return searchInternal(query, "noLimit", 5, true);
    }

    /**
     * 【工具】搜索24小时内信息: 获取关于某个主题的最新动态。
     * 此工具专门查找过去24小时内发布的内容。适用于追踪突发事件或最新进展。
     */
    public BochaResponse searchLast24Hours(String query) {
        logger.info("--- TOOL: 搜索24小时内信息 (query: {}) ---", query);
        return searchInternal(query, "oneDay", 10, true);
    }

    /**
     * 【工具】搜索本周信息: 获取关于某个主题过去一周内的主要报道。
     * 适用于进行周度舆情总结或回顾。
     */
    public BochaResponse searchLastWeek(String query) {
        logger.info("--- TOOL: 搜索本周信息 (query: {}) ---", query);
        return searchInternal(query, "oneWeek", 10, true);
    }

    private BochaResponse parseResponse(String jsonResponse) {
        BochaResponse result = new BochaResponse();
        try {
            JsonNode rootNode = mapper.readTree(jsonResponse);
            if (rootNode.has("messages")) {
                for (JsonNode message : rootNode.get("messages")) {
                    String contentType = message.get("content_type").asText();
                    String contentStr = message.get("content").asText();
                    
                    JsonNode contentNode;
                    try {
                        contentNode = mapper.readTree(contentStr);
                    } catch (Exception e) {
                        continue;
                    }

                    if ("webpage".equals(contentType)) {
                        if (contentNode.has("value")) {
                            List<WebResult> webpages = new ArrayList<>();
                            for (JsonNode item : contentNode.get("value")) {
                                WebResult webResult = mapper.treeToValue(item, WebResult.class);
                                // 处理日期字段的 fallback 逻辑
                                if (webResult.getDatePublished() == null || webResult.getDatePublished().isEmpty()) {
                                    webResult.setDatePublished(item.has("dateLastCrawled") ? item.get("dateLastCrawled").asText() : "");
                                }
                                webpages.add(webResult);
                            }
                            result.setWebpages(webpages);
                        }
                    } else if ("image".equals(contentType)) {
                        if (contentNode.has("value")) {
                            List<ImageResult> images = new ArrayList<>();
                            for (JsonNode item : contentNode.get("value")) {
                                images.add(mapper.treeToValue(item, ImageResult.class));
                            }
                            result.setImages(images);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse Bocha response", e);
        }
        return result;
    }
}
