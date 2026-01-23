package com.souyu.queryengine.service;

public interface QueryService {
    String getAiResponse(String query, String taskId);

    String getStatus(String taskId);

    String getResult(String taskId);
}
