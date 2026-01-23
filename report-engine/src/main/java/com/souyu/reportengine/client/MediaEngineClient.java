package com.souyu.reportengine.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "media-engine", url = "${app.media-engine.url:http://localhost:8082}")
public interface MediaEngineClient {

    @PostMapping("/media/research")
    Map<String, String> submitMediaSearch(@RequestBody Map<String, String> request);
}
