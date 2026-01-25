package com.souyu.reportengine.config;

import com.souyu.reportengine.consumer.ReportRequestConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Configuration
public class RedisStreamConfig {

    private final String streamKey = "task:report:request";
    private final String groupName = "report-engine-worker-group";
    
    // 生成唯一的消费者名称，支持多实例部署
    private final String consumerName = "report-worker-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private ReportRequestConsumer requestConsumer;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Bean
    public Executor redisStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：决定了单实例能并发处理多少个报告生成任务
        executor.setCorePoolSize(5); 
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("RedisStream-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Subscription subscription(Executor redisStreamExecutor) {
        ensureStreamAndGroup();

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .executor(redisStreamExecutor) // 关键：配置自定义线程池
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(redisConnectionFactory, options);

        Subscription subscription = container.receive(
                Consumer.from(groupName, consumerName),
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
