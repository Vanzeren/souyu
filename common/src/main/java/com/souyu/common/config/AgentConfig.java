package com.souyu.common.config;

import lombok.Data;

@Data
public class AgentConfig {
    private SearchConfig search = new SearchConfig();
    private OutputConfig output = new OutputConfig();

    @Data
    public static class SearchConfig {
        private int timeout = 240;
        private int contentMaxLength = 20000;
        private int maxReflections = 2;
        private int maxParagraphs = 5;
        private int maxResults = 20;
    }

    @Data
    public static class OutputConfig {
        private String dir = "reports";
        private boolean saveIntermediateStates = true;
    }
}
