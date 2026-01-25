package com.souyu.reportengine.config;

import com.souyu.reportengine.consumer.MasterStreamListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.Map;

@Configuration
public class RedisConfig {

    // 硬编码 Stream Key 和 Group Name，确保与 Producer 一致
    private final String streamKey = "task:events:stream";
    private final String groupName = "report-engine-group1";

    @Autowired
    private MasterStreamListener masterStreamListener;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Bean
    public Subscription subscription() {
        // 1. 确保 Stream 和消费者组存在
        ensureStreamAndGroup();

        // 2. 初始化 Stream 容器
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(redisConnectionFactory, options);

        // 3. 注册消费者（监听任务完成消息）
        Subscription subscription = container.receive(
                Consumer.from(groupName, "master-1"),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                masterStreamListener
        );

        container.start();
        return subscription;
    }

    /**
     * 确保 Stream 和消费者组存在
     */
    private void ensureStreamAndGroup() {
        // 1. 确保 Stream 存在
        if (Boolean.FALSE.equals(redisTemplate.hasKey(streamKey))) {
            try {
                redisTemplate.opsForStream().add(MapRecord.create(streamKey, Map.of("init", "true")));
                System.out.println("Stream created: " + streamKey);
            } catch (Exception e) {
                // ignore
            }
        }

        // 2. 创建 Consumer Group
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
            System.out.println("Consumer Group created: " + groupName);
        } catch (Exception e) {
            if (e.getMessage().contains("BUSYGROUP")) {
                System.out.println("Consumer Group already exists: " + groupName);
            } else {
                System.err.println("Failed to create consumer group: " + e.getMessage());
            }
        }
    }
}
