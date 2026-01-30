package com.souyu.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchResult(
    @JsonProperty(required = true) String search_query,
    @JsonProperty(required = true) String search_tool,
    @JsonProperty(required = true) String reasoning,
    String start_date,
    String end_date
) {}
