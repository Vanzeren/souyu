package com.souyu.mediaengine.service.impl;

import com.souyu.common.manager.StateManager;
import com.souyu.common.state.State;
import com.souyu.mediaengine.agent.BochaAgent;
import com.souyu.mediaengine.service.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MediaSeviceImpl implements MediaService {
    private static final Logger logger = LoggerFactory.getLogger(MediaSeviceImpl.class);

    @Autowired
    private StateManager stateManager;

    @Autowired
    private BochaAgent bochaAgent;

    @Override
    public String getAiResponse(String taskId,String query) {
        // Step 0: 初始化任务状态
        taskId = stateManager.initState(taskId,query,"media");
        logger.info("任务已初始化，ID: {}", taskId);

        // 异步调用 research
        // 这里直接调用 QueryAgent 的 research 方法，因为它是 public 且被 @Async 注解
        // 并且是通过 Spring 注入的 bean 调用的，所以 AOP 代理会生效，实现异步
        bochaAgent.research(query, true, taskId);
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
