package com.souyu.queryengine.controller;

import com.souyu.common.dto.QueryResponse;
import com.souyu.queryengine.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/query/research")
public class QueryController {

    private static final Logger logger = LoggerFactory.getLogger(QueryController.class);
    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
        logger.info("QueryController has been initialized.");
    }

    @PostMapping()
    public QueryResponse handleQuery(@RequestBody Map<String,String> request) {
        String query = request.get("query");
        String taskId = request.getOrDefault("taskId","");
        logger.info("Query request:{}", query);
        taskId = queryService.getAiResponse(query,taskId);
        return new QueryResponse(taskId);
    }

    @GetMapping("/status")
    public QueryResponse status(@RequestParam String taskId){
        logger.info("获取任务状态，taskId：{}" ,taskId);
        return new QueryResponse(queryService.getStatus(taskId));
    }

    @GetMapping("/result")
    public QueryResponse result(@RequestParam String taskId){
        logger.info("获取任务结果，taskId：{}" ,taskId);
        return new QueryResponse(queryService.getResult(taskId));
    }
}
