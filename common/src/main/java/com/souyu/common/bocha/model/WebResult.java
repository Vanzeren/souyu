package com.souyu.common.bocha.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class WebResult {
    private String id;
    private String name;
    private String url;
    private String snippet;
    private String summary;
    private String siteName;
    private String siteIcon;
    
    @JsonProperty("datePublished")
    private String datePublished;
    
    @JsonProperty("dateLastCrawled")
    private String dateLastCrawled;
}
