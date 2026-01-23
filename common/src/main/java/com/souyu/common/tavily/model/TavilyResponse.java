package com.souyu.common.tavily.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TavilyResponse {
    private String query;
    private String answer;
    
    @JsonProperty("results")
    private List<SearchResult> results = new ArrayList<>();
    
    @JsonProperty("images")
    private List<ImageResult> images = new ArrayList<>();
    
    @JsonProperty("response_time")
    private Double responseTime;
}
