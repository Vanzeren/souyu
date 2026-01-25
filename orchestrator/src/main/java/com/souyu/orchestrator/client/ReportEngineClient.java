package com.souyu.orchestrator.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "report-engine", url = "${app.report-engine.url:http://localhost:8083}")
public interface ReportEngineClient {

    @PostMapping("/api/v1/report/generate-internal")
    Map<String, Object> generateReportInternal(@RequestBody Map<String, Object> request);
}
