package com.souyu.common.forum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ForumSlotManager 单元测试
 */
@SpringBootTest
class ForumSlotManagerTest {

    @Autowired
    private ForumSlotManager slotManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 清理测试数据
        redisTemplate.delete(ForumConstants.KEY_SLOT_ALLOCATION);
    }

    @Test
    void testInitializeSlotAllocation_ThreeNodes() {
        List<String> nodes = Arrays.asList("forum-1", "forum-2", "forum-3");

        slotManager.initializeSlotAllocation(nodes);

        // 验证所有槽位都已分配
        Map<Integer, String> allocation = slotManager.getAllSlotAllocations();
        assertEquals(ForumConstants.SLOT_COUNT, allocation.size());

        // 验证每个节点都有槽位
        Map<String, Set<Integer>> nodeSlots = slotManager.getNodeSlotMapping();
        assertEquals(3, nodeSlots.size());

        // 验证槽位总数
        int totalSlots = nodeSlots.values().stream()
                .mapToInt(Set::size)
                .sum();
        assertEquals(ForumConstants.SLOT_COUNT, totalSlots);
    }

    @Test
    void testInitializeSlotAllocation_SingleNode() {
        List<String> nodes = Collections.singletonList("forum-1");

        slotManager.initializeSlotAllocation(nodes);

        // 所有槽位应该分配给同一个节点
        for (int i = 0; i < ForumConstants.SLOT_COUNT; i++) {
            assertEquals("forum-1", slotManager.getNodeForSlot(i));
        }
    }

    @Test
    void testGetSlotsForNode() {
        List<String> nodes = Arrays.asList("forum-1", "forum-2");
        slotManager.initializeSlotAllocation(nodes);

        Set<Integer> slotsForNode1 = slotManager.getSlotsForNode("forum-1");
        Set<Integer> slotsForNode2 = slotManager.getSlotsForNode("forum-2");

        // 每个节点应该有槽位
        assertFalse(slotsForNode1.isEmpty());
        assertFalse(slotsForNode2.isEmpty());

        // 槽位不应该重叠
        Set<Integer> intersection = new HashSet<>(slotsForNode1);
        intersection.retainAll(slotsForNode2);
        assertTrue(intersection.isEmpty(), "Slots should not overlap");
    }

    @Test
    void testExpandNode() {
        // 初始两个节点
        slotManager.initializeSlotAllocation(Arrays.asList("forum-1", "forum-2"));

        int slotsBefore = slotManager.getSlotsForNode("forum-1").size()
                + slotManager.getSlotsForNode("forum-2").size();
        assertEquals(ForumConstants.SLOT_COUNT, slotsBefore);

        // 扩容：添加新节点，迁移 100 个槽位
        slotManager.expandNode("forum-3", 100);

        // 验证新节点有槽位
        Set<Integer> newNodeSlots = slotManager.getSlotsForNode("forum-3");
        assertEquals(100, newNodeSlots.size());

        // 验证原节点槽位减少
        int totalAfter = slotManager.getSlotsForNode("forum-1").size()
                + slotManager.getSlotsForNode("forum-2").size()
                + slotManager.getSlotsForNode("forum-3").size();
        assertEquals(ForumConstants.SLOT_COUNT, totalAfter);
    }

    @Test
    void testShrinkNode() {
        // 初始三个节点
        slotManager.initializeSlotAllocation(Arrays.asList("forum-1", "forum-2", "forum-3"));

        // 缩容：移除 forum-3，槽位分配给 forum-1 和 forum-2
        slotManager.shrinkNode("forum-3", Arrays.asList("forum-1", "forum-2"));

        // 验证 forum-3 没有槽位了
        assertTrue(slotManager.getSlotsForNode("forum-3").isEmpty());

        // 验证 forum-1 和 forum-2 有新的槽位
        assertFalse(slotManager.getSlotsForNode("forum-1").isEmpty());
        assertFalse(slotManager.getSlotsForNode("forum-2").isEmpty());

        // 验证总槽位数不变
        int totalSlots = slotManager.getSlotsForNode("forum-1").size()
                + slotManager.getSlotsForNode("forum-2").size();
        assertEquals(ForumConstants.SLOT_COUNT, totalSlots);
    }

    @Test
    void testEmergencyMigrate() {
        // 初始三个节点
        slotManager.initializeSlotAllocation(Arrays.asList("forum-1", "forum-2", "forum-3"));

        Set<Integer> originalSlots = slotManager.getSlotsForNode("forum-2");
        assertFalse(originalSlots.isEmpty());

        // 紧急迁移：forum-2 宕机
        slotManager.emergencyMigrate("forum-2", Arrays.asList("forum-1", "forum-3"));

        // 验证 forum-2 的槽位被迁移
        assertTrue(slotManager.getSlotsForNode("forum-2").isEmpty());

        // 验证槽位被分配给其他节点
        int totalSlots = slotManager.getSlotsForNode("forum-1").size()
                + slotManager.getSlotsForNode("forum-3").size();
        assertEquals(ForumConstants.SLOT_COUNT, totalSlots);
    }

    @Test
    void testValidateAllocation() {
        // 有效分配
        slotManager.initializeSlotAllocation(Arrays.asList("forum-1", "forum-2"));
        assertTrue(slotManager.validateAllocation());

        // 删除一些槽位分配，使其无效
        redisTemplate.opsForHash().delete(ForumConstants.KEY_SLOT_ALLOCATION, "0", "1", "2");
        assertFalse(slotManager.validateAllocation());
    }

    @Test
    void testGetAllocationStats() {
        slotManager.initializeSlotAllocation(Arrays.asList("forum-1", "forum-2", "forum-3"));

        String stats = slotManager.getAllocationStats();
        assertNotNull(stats);
        assertTrue(stats.contains("forum-1"));
        assertTrue(stats.contains("forum-2"));
        assertTrue(stats.contains("forum-3"));
    }
}
