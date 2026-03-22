package com.souyu.common.forum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ForumFailoverManager 单元测试
 */
@SpringBootTest
class ForumFailoverManagerTest {

    @Autowired
    private ForumFailoverManager failoverManager;

    @Autowired
    private ForumSlotManager slotManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        redisTemplate.keys("forum:*").forEach(redisTemplate::delete);
        redisTemplate.keys("task:*:forum").forEach(redisTemplate::delete);
    }

    private void createHealthyNode(String nodeId) {
        String nodeKey = ForumConstants.KEY_NODE_PREFIX + nodeId;
        redisTemplate.opsForHash().putAll(nodeKey, Map.of(
                "nodeId", nodeId,
                "host", "localhost",
                "port", "8083",
                "load", "0",
                "capacity", "20",
                "lastHeartbeat", String.valueOf(System.currentTimeMillis())
        ));
        redisTemplate.expire(nodeKey, ForumConstants.NODE_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void createDeadNode(String nodeId) {
        String nodeKey = ForumConstants.KEY_NODE_PREFIX + nodeId;
        redisTemplate.opsForHash().putAll(nodeKey, Map.of(
                "nodeId", nodeId,
                "host", "localhost",
                "port", "8083",
                "load", "5",
                "capacity", "20",
                "lastHeartbeat", String.valueOf(System.currentTimeMillis() - 30000) // 30秒前的心跳
        ));
        // 不设置 TTL，让 key 存在但心跳过期
    }

    @Test
    void testDetectDeadNodes() {
        // 创建健康节点
        createHealthyNode("forum-healthy");

        // 创建死亡节点（心跳过期）
        createDeadNode("forum-dead");

        Set<String> deadNodes = failoverManager.detectDeadNodes();

        assertTrue(deadNodes.contains("forum-dead"), "Should detect dead node");
        assertFalse(deadNodes.contains("forum-healthy"), "Should not detect healthy node");
    }

    @Test
    void testGetHealthyNodes() {
        createHealthyNode("forum-1");
        createHealthyNode("forum-2");
        createDeadNode("forum-dead");

        List<String> healthyNodes = failoverManager.getAllHealthyNodes();

        assertTrue(healthyNodes.contains("forum-1"));
        assertTrue(healthyNodes.contains("forum-2"));
        assertFalse(healthyNodes.contains("forum-dead"));
    }

    @Test
    void testPerformFailover() {
        // 初始化槽位
        slotManager.initializeSlotAllocation(Arrays.asList("forum-1", "forum-2", "forum-dead"));

        // 创建健康节点和死亡节点
        createHealthyNode("forum-1");
        createHealthyNode("forum-2");
        createDeadNode("forum-dead");

        // 为死亡节点添加一些任务
        String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, "forum-dead");
        redisTemplate.opsForSet().add(tasksKey, "task-1", "task-2", "task-3");

        // 创建任务绑定
        for (String taskId : Arrays.asList("task-1", "task-2", "task-3")) {
            String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);
            redisTemplate.opsForHash().putAll(bindingKey, Map.of(
                    "nodeId", "forum-dead",
                    "status", "ACTIVE"
            ));
        }

        // 执行故障转移
        failoverManager.performFailover("forum-dead");

        // 验证死亡节点的槽位被迁移
        assertTrue(slotManager.getSlotsForNode("forum-dead").isEmpty());

        // 验证死亡节点的数据被清理
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(
                ForumConstants.KEY_NODE_PREFIX + "forum-dead")));

        // 验证任务被标记为 FAILED
        for (String taskId : Arrays.asList("task-1", "task-2", "task-3")) {
            String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);
            String status = (String) redisTemplate.opsForHash().get(bindingKey, "status");
            assertEquals("FAILED", status, "Task should be marked as FAILED");
        }
    }

    @Test
    void testGetClusterHealth() {
        createHealthyNode("forum-1");
        createHealthyNode("forum-2");
        createDeadNode("forum-dead");

        // 设置负载
        redisTemplate.opsForHash().put(
                ForumConstants.KEY_NODE_PREFIX + "forum-1", "load", "5");
        redisTemplate.opsForHash().put(
                ForumConstants.KEY_NODE_PREFIX + "forum-2", "load", "10");

        ForumFailoverManager.HealthStatus status = failoverManager.getClusterHealth();

        assertEquals("DEGRADED", status.getStatus()); // 有一个节点死亡
        assertEquals(3, status.getTotalNodes());
        assertEquals(2, status.getHealthyNodes());
        assertEquals(15, status.getTotalLoad()); // 5 + 10
        assertEquals(40, status.getTotalCapacity()); // 20 + 20
    }

    @Test
    void testGetClusterHealth_AllHealthy() {
        createHealthyNode("forum-1");
        createHealthyNode("forum-2");

        ForumFailoverManager.HealthStatus status = failoverManager.getClusterHealth();

        assertEquals("HEALTHY", status.getStatus());
        assertEquals(2, status.getTotalNodes());
        assertEquals(2, status.getHealthyNodes());
    }

    @Test
    void testGetClusterHealth_NoNodes() {
        ForumFailoverManager.HealthStatus status = failoverManager.getClusterHealth();

        assertEquals("NO_NODES", status.getStatus());
    }
}
