package com.souyu.queryengine.service.impl;

import com.souyu.common.state.State;
import com.souyu.queryengine.agent.QueryAgent;
import com.souyu.common.manager.StateManager;
import com.souyu.queryengine.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QueryServiceImpl implements QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryServiceImpl.class);

    @Autowired
    private StateManager stateManager;
    
    @Autowired
    private QueryAgent queryAgent;

    public QueryServiceImpl() {
        logger.info("QueryServiceImpl has been initialized.");
    }

    @Override
    public String getAiResponse(String query, String taskId) {
        // Step 0: 初始化任务状态
        taskId = stateManager.initState(taskId,query,"query");
        logger.info("任务已初始化，ID: {}", taskId);
        
        // 异步调用 research
        // 这里直接调用 QueryAgent 的 research 方法，因为它是 public 且被 @Async 注解
        // 并且是通过 Spring 注入的 bean 调用的，所以 AOP 代理会生效，实现异步
        queryAgent.research(query, true, taskId);
        logger.info("异步任务已提交，taskId: {}", taskId);
        return taskId;
    }
    
    @Override
    public String getStatus(String taskId){
        return stateManager.getStatus(taskId);
    }

    @Override
    public String getResult(String taskId) {
        State state = stateManager.getState(taskId);
        if (state != null && state.isCompleted()) {
            return state.getFinalReport();
        }
        return null;
    }
}
