package com.souyu.common.tavily.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SearchResult {
    private String title;
    private String url;
    private String content;
    private Double score;
    
    @JsonProperty("raw_content")
    private String rawContent;
    
    @JsonProperty("published_date")
    private String publishedDate;
}
