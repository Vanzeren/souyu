package com.souyu.common.tavily.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ImageResult {
    private String url;
    private String description;
}
