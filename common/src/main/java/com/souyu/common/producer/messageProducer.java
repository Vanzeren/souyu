package com.souyu.common.producer;

import org.redisson.Redisson;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
public class messageProducer {

    private static final Logger logger = LoggerFactory.getLogger(messageProducer.class);
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;


    /**
     * 发送消息到 Redis Stream
     *
     * @param streamKey Stream 的键名
     * @param message   消息内容 Map
     * @return 生成的消息 ID
     */
    public String sendMessage(String streamKey, Map<String, String> message) {
        try {
            // 构建记录
            ObjectRecord<String, Map<String, String>> record = StreamRecords.newRecord()
                    .in(streamKey)
                    .ofObject(message)
                    .withId(RecordId.autoGenerate());

            // 发送消息
            RecordId recordId = this.redisTemplate.opsForStream().add(record);

            if (recordId != null) {
                logger.debug("Sent message to stream [{}]: {}", streamKey, recordId);
                return recordId.getValue();
            }
            return null;
        } catch (Exception e) {
            logger.error("Failed to send message to stream [{}]", streamKey, e);
            throw e;
        }
    }

    /**
     * 触发 Master 报告生成 (发送 WORKER_COMPLETED 事件)
     * 
     * @param taskId 任务ID
     * @param engine 引擎名称 (query, media, forum)
     */
    public void triggerMasterReport(String taskId, String engine) {
        // 统一使用 task:events:stream
        String streamKey = "task:events:stream";

        Map<String, String> message = new HashMap<>();
        message.put("taskId", taskId);
        message.put("type", "WORKER_COMPLETED"); // 明确事件类型
        message.put("source", engine);           // 明确来源
        message.put("timestamp", LocalDateTime.now().toString());
        
        try {
            // 构建记录
            ObjectRecord<String, Map<String, String>> record = StreamRecords.newRecord()
                    .in(streamKey)
                    .ofObject(message)
                    .withId(RecordId.autoGenerate());

            // 发送消息
            RecordId recordId = this.redisTemplate.opsForStream().add(record);

            if (recordId != null) {
                logger.info("Sent WORKER_COMPLETED event to stream [{}] for task {}, source: {}", streamKey, taskId, engine);
            }
        } catch (Exception e) {
            logger.error("Failed to send message to stream [{}]", streamKey, e);
            throw e;
        }
    }
}
