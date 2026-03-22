package com.souyu.common.forum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Forum 消息生产者
 * 供 Query/Media/Report Engine 发送消息到 Forum
 */
@Service
@Slf4j
public class ForumMessageProducer {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ForumServiceRouter router;

    /**
     * 发送消息到 Forum (核心方法)
     * 自动路由到正确的 Forum 节点
     *
     * @param taskId  任务ID
     * @param message 消息对象
     * @throws ForumRoutingException 当路由失败或发送失败时抛出
     */
    public void send(String taskId, ForumMessage message) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId cannot be null or empty");
        }
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null");
        }

        // 1. 获取路由节点
        ForumNode node = router.getOrBindNode(taskId);

        // 2. 构建 Stream Key
        String streamKey = node.getStreamKey();

        // 3. 检查 Stream 长度（限流保护）
        Long streamLen = redisTemplate.opsForStream().size(streamKey);
        if (streamLen != null && streamLen > ForumConstants.MAX_STREAM_LENGTH) {
            log.error("Forum node {} stream overflow: {} messages", node.getNodeId(), streamLen);
            throw new ForumRoutingException("Forum node overloaded: " + node.getNodeId());
        }

        // 4. 构建消息记录
        Map<String, String> record = new HashMap<>();
        record.put("taskId", message.getTaskId() != null ? message.getTaskId() : taskId);
        record.put("type", message.getType() != null ? message.getType().name() : MessageType.CONTENT.name());
        record.put("content", message.getContent() != null ? message.getContent() : "");
        record.put("sourceEngine", message.getSourceEngine() != null ? message.getSourceEngine() : "unknown");
        record.put("timestamp", String.valueOf(message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis()));

        // 5. 发送消息
        try {
            RecordId recordId = redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord()
                            .in(streamKey)
                            .ofObject(record)
                            .withId(RecordId.autoGenerate()));

            if (recordId != null) {
                log.debug("Sent message to {} for task {}, recordId: {}",
                        streamKey, taskId, recordId.getValue());
            }

        } catch (Exception e) {
            log.error("Failed to send message to {} for task {}", streamKey, taskId, e);
            throw new ForumRoutingException("Failed to send message", e);
        }

        // 6. 刷新绑定 TTL（续租）
        refreshBindingTTL(taskId);
    }

    /**
     * 便捷方法：发送内容消息
     *
     * @param taskId       任务ID
     * @param content      消息内容
     * @param sourceEngine 来源引擎
     */
    public void sendContent(String taskId, String content, String sourceEngine) {
        ForumMessage message = ForumMessage.content(taskId, content, sourceEngine);
        send(taskId, message);
    }

    /**
     * 便捷方法：发送状态消息
     *
     * @param taskId       任务ID
     * @param status       状态内容
     * @param sourceEngine 来源引擎
     */
    public void sendStatus(String taskId, String status, String sourceEngine) {
        ForumMessage message = new ForumMessage();
        message.setTaskId(taskId);
        message.setType(MessageType.STATUS);
        message.setContent(status);
        message.setSourceEngine(sourceEngine);
        message.setTimestamp(System.currentTimeMillis());
        send(taskId, message);
    }

    /**
     * 便捷方法：发送完成消息
     *
     * @param taskId       任务ID
     * @param sourceEngine 来源引擎
     */
    public void sendComplete(String taskId, String sourceEngine) {
        ForumMessage message = ForumMessage.complete(taskId, sourceEngine);
        send(taskId, message);
    }

    /**
     * 便捷方法：发送错误消息
     *
     * @param taskId       任务ID
     * @param errorMessage 错误信息
     * @param sourceEngine 来源引擎
     */
    public void sendError(String taskId, String errorMessage, String sourceEngine) {
        ForumMessage message = ForumMessage.error(taskId, errorMessage, sourceEngine);
        send(taskId, message);
    }

    /**
     * 刷新绑定 TTL（续租）
     * 只要任务还在活跃发消息，绑定就永不过期
     *
     * @param taskId 任务ID
     */
    private void refreshBindingTTL(String taskId) {
        String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);
        try {
            redisTemplate.expire(bindingKey, ForumConstants.BINDING_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to refresh binding TTL for task {}", taskId, e);
        }
    }

    /**
     * 获取任务当前绑定的节点信息
     * 用于调试和监控
     *
     * @param taskId 任务ID
     * @return 节点信息，无绑定返回 null
     */
    public ForumNode getBoundNode(String taskId) {
        try {
            return router.getOrBindNode(taskId);
        } catch (ForumRoutingException e) {
            log.warn("Failed to get bound node for task {}", taskId, e);
            return null;
        }
    }
}
