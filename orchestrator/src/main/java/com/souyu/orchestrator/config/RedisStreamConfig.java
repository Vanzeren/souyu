package com.souyu.orchestrator.config;

import com.souyu.orchestrator.consumer.MasterStreamListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.Map;

@Configuration
public class RedisStreamConfig {

    private final String streamKey = "task:events:stream";
    private final String groupName = "orchestrator-group";

    @Autowired
    private MasterStreamListener streamListener;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Bean
    public Subscription subscription() {
        ensureStreamAndGroup();

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(redisConnectionFactory, options);

        Subscription subscription = container.receive(
                Consumer.from(groupName, "orchestrator-1"),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                streamListener
        );

        container.start();
        return subscription;
    }

    private void ensureStreamAndGroup() {
        // 1. 确保 Stream 存在
        if (Boolean.FALSE.equals(redisTemplate.hasKey(streamKey))) {
            try {
                redisTemplate.opsForStream().add(MapRecord.create(streamKey, Map.of("init", "true")));
            } catch (Exception e) {
                // ignore
            }
        }

        // 2. 检查并创建 Consumer Group
        try {
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
            boolean groupExists = groups.stream().anyMatch(g -> g.groupName().equals(groupName));
            
            if (!groupExists) {
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
            }
        } catch (Exception e) {
            // 如果 groups 命令失败（例如 key 不存在），尝试直接创建
            try {
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
            } catch (Exception ex) {
                // 忽略 BUSYGROUP 错误
            }
        }
    }
}
