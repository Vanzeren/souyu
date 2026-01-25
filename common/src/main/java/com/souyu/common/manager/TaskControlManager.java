package com.souyu.common.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskControlManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskControlManager.class);
    private static final String CHANNEL_NAME = "task:control";
    
    // 存储被取消的任务ID
    private final ConcurrentHashMap<String, Boolean> cancelledTasks = new ConcurrentHashMap<>();

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 发送取消指令 (Master 调用)
     */
    public void sendCancelCommand(String taskId) {
        String message = "CANCEL:" + taskId;
        redisTemplate.convertAndSend(CHANNEL_NAME, message);
        logger.info("Sent cancel command for task: {}", taskId);
    }

    /**
     * 检查任务是否被取消 (Worker 调用)
     */
    public boolean isCancelled(String taskId) {
        return cancelledTasks.containsKey(taskId);
    }
    
    /**
     * 清理任务状态 (任务结束后调用)
     */
    public void clearTask(String taskId) {
        cancelledTasks.remove(taskId);
    }

    /**
     * 接收消息的处理方法
     */
    public void handleMessage(String message) {
        if (message != null && message.startsWith("CANCEL:")) {
            String taskId = message.substring(7);
            cancelledTasks.put(taskId, true);
            logger.warn("Received CANCEL command for task: {}", taskId);
        }
    }

    @Bean
    RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                            MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new PatternTopic(CHANNEL_NAME));
        return container;
    }

    @Bean
    MessageListenerAdapter listenerAdapter(TaskControlManager manager) {
        return new MessageListenerAdapter(manager, "handleMessage");
    }
}
