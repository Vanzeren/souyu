package com.souyu.reportengine.config;

import com.souyu.reportengine.consumer.ReportRequestConsumer;
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

    private final String streamKey = "task:report:request";
    private final String groupName = "report-engine-worker-group";

    @Autowired
    private ReportRequestConsumer requestConsumer;

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
                Consumer.from(groupName, "report-worker-1"),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                requestConsumer
        );

        container.start();
        return subscription;
    }

    private void ensureStreamAndGroup() {
        if (Boolean.FALSE.equals(redisTemplate.hasKey(streamKey))) {
            try {
                redisTemplate.opsForStream().add(MapRecord.create(streamKey, Map.of("init", "true")));
            } catch (Exception e) {
                // ignore
            }
        }

        try {
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
            boolean groupExists = groups.stream().anyMatch(g -> g.groupName().equals(groupName));
            
            if (!groupExists) {
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
            }
        } catch (Exception e) {
            try {
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
            } catch (Exception ex) {
                // ignore
            }
        }
    }
}
