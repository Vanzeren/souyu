package com.souyu.mediaengine.controller;

import com.souyu.mediaengine.service.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/media/research")
public class MediaAgentController {

    private static final Logger logger = LoggerFactory.getLogger(MediaAgentController.class);

    @Autowired
    private final MediaService mediaService;

    public MediaAgentController(MediaService mediaService ) {
        this.mediaService = mediaService;
        logger.info("MediaAgentController has been initialized.");
    }

    @PostMapping()
    public com.souyu.dto.QueryResponse handleQuery(@RequestBody Map<String,String> request) {
        String query = request.get("query");
        String taskId = request.getOrDefault("taskId","");
        logger.info("Query request:{}", query);
        taskId = mediaService.getAiResponse(taskId,query);
        return new com.souyu.dto.QueryResponse(taskId);
    }

    @GetMapping("/status")
    public com.souyu.dto.QueryResponse status(@RequestParam String taskId){
        logger.info("获取任务状态，taskId：{}" ,taskId);
        return new com.souyu.dto.QueryResponse(mediaService.getStatus(taskId));
    }

    @GetMapping("/result")
    public com.souyu.dto.QueryResponse result(@RequestParam String taskId){
        logger.info("获取任务结果，taskId：{}" ,taskId);
        return new com.souyu.dto.QueryResponse(mediaService.getResult(taskId));
    }
}
