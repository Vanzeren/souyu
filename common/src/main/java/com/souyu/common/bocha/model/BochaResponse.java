package com.souyu.common.bocha.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class BochaResponse {
    
    @JsonProperty("webpage")
    private List<WebResult> webpages = new ArrayList<>();
    
    @JsonProperty("image")
    private List<ImageResult> images = new ArrayList<>();
    
    // 可以根据需要添加其他模态卡片字段
}
