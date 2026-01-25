package com.souyu.orchestrator.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "media-engine", url = "${app.media-engine.url:http://localhost:8082}")
public interface MediaEngineClient {

    @PostMapping("/media/research")
    void submitMediaSearch(@RequestBody Map<String, String> request);
}
