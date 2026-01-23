package com.souyu.common.bocha.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ImageResult {
    private String id;
    private String contentUrl;
    private String hostPageUrl;
    private int width;
    private int height;
}
