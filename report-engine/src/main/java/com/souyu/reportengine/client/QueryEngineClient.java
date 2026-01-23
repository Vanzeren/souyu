package com.souyu.reportengine.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "query-engine", url = "${app.query-engine.url:http://localhost:8081}")
public interface QueryEngineClient {

    @PostMapping("/query/research")
    Map<String, String> submitQuery(@RequestBody Map<String, String> request);
}
