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
     * @param streamKey Stream 的键名
     * @param message 消息内容 Map
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

    public void triggerMasterReport(String taskId) {
        String streamKey= "task:prefinished:stream";
        RAtomicLong atomicCount = redissonClient.getAtomicLong("task:count:" + taskId);
        // decrementAndGet 会返回减完之后的最新值
        long remaining = atomicCount.decrementAndGet();

        if (remaining == 1) {
            // 说明是最后一个执行完的 worker，可以触发后续逻辑
            Map<String, String> message = new HashMap<>();
            message.put("taskId", taskId);
            message.put("workerTime", LocalDateTime.now().toString());
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
                }
            } catch (Exception e) {
                logger.error("Failed to send message to stream [{}]", streamKey, e);
                throw e;
            }
        }
    }
}
