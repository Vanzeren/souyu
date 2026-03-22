package com.souyu.common.forum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ForumServiceRouter 单元测试
 */
@SpringBootTest
class ForumServiceRouterTest {

    @Autowired
    private ForumServiceRouter router;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        redisTemplate.keys("forum:*").forEach(redisTemplate::delete);
        redisTemplate.keys("task:*:forum").forEach(redisTemplate::delete);
    }

    @Test
    void testGetSlot_Consistency() {
        // 测试槽位计算的一致性
        String taskId = "test-task-123";

        int slot1 = router.getSlot(taskId);
        int slot2 = router.getSlot(taskId);
        int slot3 = router.getSlot(taskId);

        assertEquals(slot1, slot2, "Slot calculation should be consistent");
        assertEquals(slot2, slot3, "Slot calculation should be consistent");
    }

    @Test
    void testGetSlot_Range() {
        // 测试槽位范围在 0-1023
        for (int i = 0; i < 100; i++) {
            String taskId = "task-" + i;
            int slot = router.getSlot(taskId);
            assertTrue(slot >= 0 && slot < ForumConstants.SLOT_COUNT,
                    "Slot should be in range [0, 1023), got: " + slot);
        }
    }

    @Test
    void testInitializeSlotAllocation() {
        // 初始化槽位分配
        List<String> nodes = Arrays.asList("forum-1", "forum-2", "forum-3");

        ForumSlotManager slotManager = new ForumSlotManager();
        slotManager.initializeSlotAllocation(nodes);

        // 验证所有槽位都已分配
        for (int i = 0; i < ForumConstants.SLOT_COUNT; i++) {
            String nodeId = slotManager.getNodeForSlot(i);
            assertNotNull(nodeId, "Slot " + i + " should be assigned");
            assertTrue(nodes.contains(nodeId), "Node should be in the list");
        }
    }

    @Test
    void testGetOrBindNode_NewTask() {
        // 先初始化槽位
        ForumSlotManager slotManager = new ForumSlotManager();
        slotManager.initializeSlotAllocation(Arrays.asList("forum-test-8083"));

        // 注册节点
        String nodeKey = ForumConstants.KEY_NODE_PREFIX + "forum-test-8083";
        redisTemplate.opsForHash().put(nodeKey, "nodeId", "forum-test-8083");
        redisTemplate.opsForHash().put(nodeKey, "host", "localhost");
        redisTemplate.opsForHash().put(nodeKey, "port", "8083");
        redisTemplate.opsForHash().put(nodeKey, "load", "0");
        redisTemplate.opsForHash().put(nodeKey, "capacity", "20");

        // 测试新任务绑定
        String taskId = "new-task-" + System.currentTimeMillis();
        ForumNode node = router.getOrBindNode(taskId);

        assertNotNull(node);
        assertEquals("forum-test-8083", node.getNodeId());
    }

    @Test
    void testGetOrBindNode_ExistingBinding() {
        // 初始化槽位和节点
        ForumSlotManager slotManager = new ForumSlotManager();
        slotManager.initializeSlotAllocation(Arrays.asList("forum-test-8083"));

        String nodeKey = ForumConstants.KEY_NODE_PREFIX + "forum-test-8083";
        redisTemplate.opsForHash().putAll(nodeKey, java.util.Map.of(
                "nodeId", "forum-test-8083",
                "host", "localhost",
                "port", "8083",
                "load", "0",
                "capacity", "20"
        ));

        String taskId = "existing-task-" + System.currentTimeMillis();

        // 第一次绑定
        ForumNode node1 = router.getOrBindNode(taskId);

        // 第二次应该返回相同的节点
        ForumNode node2 = router.getOrBindNode(taskId);

        assertEquals(node1.getNodeId(), node2.getNodeId());
    }

    @Test
    void testConcurrentBinding() throws InterruptedException {
        // 初始化槽位和节点
        ForumSlotManager slotManager = new ForumSlotManager();
        slotManager.initializeSlotAllocation(Arrays.asList("forum-test-8083"));

        String nodeKey = ForumConstants.KEY_NODE_PREFIX + "forum-test-8083";
        redisTemplate.opsForHash().putAll(nodeKey, java.util.Map.of(
                "nodeId", "forum-test-8083",
                "host", "localhost",
                "port", "8083",
                "load", "0",
                "capacity", "20"
        ));

        String taskId = "concurrent-task-" + System.currentTimeMillis();
        int threadCount = 10;
        ForumNode[] results = new ForumNode[threadCount];

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = router.getOrBindNode(taskId);
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // 所有线程应该得到相同的节点
        String expectedNodeId = results[0].getNodeId();
        for (ForumNode node : results) {
            assertNotNull(node);
            assertEquals(expectedNodeId, node.getNodeId(),
                    "All threads should bind to the same node");
        }
    }
}
