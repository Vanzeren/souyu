package com.souyu.mediaengine.service;

public interface MediaService {
    String getAiResponse(String taskId,String query);

    String getStatus(String taskId);

    String getResult(String taskId);
}
