package com.souyu.common.context;

import com.souyu.common.tavily.model.TavilyResponse;

public class SearchContext {
    private static final ThreadLocal<TavilyResponse> LAST_RESPONSE = new ThreadLocal<>();

    public static void setLastResponse(TavilyResponse response) {
        LAST_RESPONSE.set(response);
    }

    public static TavilyResponse getLastResponse() {
        return LAST_RESPONSE.get();
    }

    public static void clear() {
        LAST_RESPONSE.remove();
    }
}
