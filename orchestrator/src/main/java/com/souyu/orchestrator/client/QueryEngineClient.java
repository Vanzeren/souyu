package com.souyu.orchestrator.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "query-engine", url = "${app.query-engine.url:http://localhost:8081}")
public interface QueryEngineClient {

    @PostMapping("/query/research")
    void submitQuery(@RequestBody Map<String, String> request);
}
