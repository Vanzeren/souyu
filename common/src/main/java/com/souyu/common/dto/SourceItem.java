package com.souyu.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceItem(
    String title,
    String url,
    String content,
    Double score,
    String publishedDate,
    String source // e.g., "Tavily", "Bocha"
) {}
