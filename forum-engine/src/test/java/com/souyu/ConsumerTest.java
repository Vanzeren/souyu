package com.souyu;

import com.souyu.forum.agent.consumer;
import com.souyu.forum.agent.consumer.ForumLog;
import com.souyu.forum.agent.consumer.OneLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
public class ConsumerTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private consumer consumerBean;

    @Test
    public void testConsumeMessageAndSaveToMongo() throws InterruptedException {
        // 1. 准备测试数据
        String taskId = UUID.randomUUID().toString();
        String content = "Test log content " + System.currentTimeMillis();
        String engine = "test-engine";
        String streamKey = "forum";

        Map<String, String> messageMap = new HashMap<>();
        messageMap.put("taskId", taskId);
        messageMap.put("content", content);
        messageMap.put("engine", engine);

        // 2. 发送消息到 Redis Stream
        ObjectRecord<String, Map<String, String>> record = StreamRecords.newRecord()
                .in(streamKey)
                .ofObject(messageMap)
                .withId(RecordId.autoGenerate());

        redisTemplate.opsForStream().add(record);
        System.out.println("Sent message to Redis Stream: " + messageMap);

        // 3. 等待 Consumer 处理
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            // 4. 验证 MongoDB 中是否存在数据
            ForumLog forumLog = mongoTemplate.findById(taskId, ForumLog.class, "forum_logs");
            assertNotNull(forumLog, "ForumLog should exist in MongoDB");
            assertNotNull(forumLog.getContent(), "Content list should not be null");
            assertFalse(forumLog.getContent().isEmpty(), "Content list should not be empty");

            OneLog oneLog = forumLog.getContent().get(0);
            assertEquals(engine, oneLog.getEngine());
            assertEquals(content, oneLog.getContent());
            assertTrue(oneLog.getTimeStamp() > 0);
        });
        
        System.out.println("Successfully verified data in MongoDB!");

        // 5. 测试追加日志
        String content2 = "Second log content";
        messageMap.put("content", content2);
        ObjectRecord<String, Map<String, String>> record2 = StreamRecords.newRecord()
                .in(streamKey)
                .ofObject(messageMap)
                .withId(RecordId.autoGenerate());
        redisTemplate.opsForStream().add(record2);
        System.out.println("Sent second message to Redis Stream");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ForumLog forumLog = mongoTemplate.findById(taskId, ForumLog.class, "forum_logs");
            assertNotNull(forumLog);
            assertEquals(2, forumLog.getContent().size(), "Should have 2 logs now");
            assertEquals(content2, forumLog.getContent().get(1).getContent());
        });
        
        System.out.println("Successfully verified appended data in MongoDB!");
    }
}
