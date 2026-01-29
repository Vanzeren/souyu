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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskControlManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskControlManager.class);
    private static final String CHANNEL_NAME = "task:control";
    
    // 存储被取消的任务ID及其对应的 Engine 集合
    // Key: taskId, Value: Set of engine names ("ALL" for entire task)
    private final ConcurrentHashMap<String, Set<String>> cancelledTasks = new ConcurrentHashMap<>();

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 发送取消指令 (Master 调用) - 取消整个任务
     */
    public void sendCancelCommand(String taskId) {
        sendCancelCommand(taskId, "ALL");
    }

    /**
     * 发送取消指令 (Master 调用) - 取消特定 Engine
     */
    public void sendCancelCommand(String taskId, String engine) {
        String message = "CANCEL:" + taskId + ":" + engine;
        redisTemplate.convertAndSend(CHANNEL_NAME, message);
        logger.info("Sent cancel command for task: {}, engine: {}", taskId, engine);
    }
    
    /**
     * 撤销取消指令 (Master 调用) - 用于重试场景
     * 需要广播 REVOKE 消息，以便所有节点同步清除状态
     */
    public void revokeCancelCommand(String taskId, String engine) {
        String message = "REVOKE:" + taskId + ":" + engine;
        redisTemplate.convertAndSend(CHANNEL_NAME, message);
        logger.info("Sent revoke cancel command for task: {}, engine: {}", taskId, engine);
    }

    /**
     * 检查任务是否被取消 (Worker 调用)
     * @param taskId 任务ID
     * @param engine 当前 Worker 的 Engine 名称
     */
    public boolean isCancelled(String taskId, String engine) {
        Set<String> engines = cancelledTasks.get(taskId);
        if (engines == null) return false;
        return engines.contains("ALL") || engines.contains(engine);
    }
    
    /**
     * 检查任务是否被全局取消
     */
    public boolean isCancelled(String taskId) {
        return isCancelled(taskId, "ALL");
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
        if (message == null) return;
        
        if (message.startsWith("CANCEL:")) {
            String[] parts = message.split(":");
            if (parts.length >= 2) {
                String taskId = parts[1];
                String engine = parts.length > 2 ? parts[2] : "ALL";
                
                cancelledTasks.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(engine);
                logger.warn("Received CANCEL command for task: {}, engine: {}", taskId, engine);
            }
        } else if (message.startsWith("REVOKE:")) {
            String[] parts = message.split(":");
            if (parts.length >= 2) {
                String taskId = parts[1];
                String engine = parts.length > 2 ? parts[2] : "ALL";
                
                Set<String> engines = cancelledTasks.get(taskId);
                if (engines != null) {
                    engines.remove(engine);
                    // 如果是 REVOKE ALL，或者集合为空，是否需要移除 key? 
                    // 简单起见，只移除 set 中的元素
                    if ("ALL".equals(engine)) {
                        engines.clear(); // 清除所有
                    }
                    logger.info("Received REVOKE command for task: {}, engine: {}", taskId, engine);
                }
            }
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
