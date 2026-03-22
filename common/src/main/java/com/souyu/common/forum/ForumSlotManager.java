package com.souyu.common.forum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Forum 槽位分配管理器
 * 负责初始化槽位分配、扩容缩容时的槽位迁移
 */
@Component
@Slf4j
public class ForumSlotManager {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 初始化槽位分配
     * 将 1024 个槽位平均分配给所有节点
     *
     * @param nodeIds 节点ID列表
     */
    public void initializeSlotAllocation(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("Node list cannot be empty");
        }

        log.info("Initializing slot allocation for {} nodes: {}", nodeIds.size(), nodeIds);

        // 清空现有分配
        redisTemplate.delete(ForumConstants.KEY_SLOT_ALLOCATION);

        int totalSlots = ForumConstants.SLOT_COUNT;
        int nodeCount = nodeIds.size();
        int slotsPerNode = totalSlots / nodeCount;
        int remainder = totalSlots % nodeCount;

        int currentSlot = 0;
        for (int i = 0; i < nodeCount; i++) {
            String nodeId = nodeIds.get(i);
            // 最后一个节点分得剩余的槽位
            int slotsForThisNode = (i == nodeCount - 1) ?
                    slotsPerNode + remainder : slotsPerNode;

            for (int j = 0; j < slotsForThisNode; j++) {
                redisTemplate.opsForHash().put(
                        ForumConstants.KEY_SLOT_ALLOCATION,
                        String.valueOf(currentSlot),
                        nodeId
                );
                currentSlot++;
            }

            log.info("Assigned slots {}-{} to node {}",
                    currentSlot - slotsForThisNode, currentSlot - 1, nodeId);
        }

        log.info("Slot allocation initialized: {} slots to {} nodes", totalSlots, nodeCount);
    }

    /**
     * 获取槽位分配信息
     *
     * @param slot 槽位号 (0-1023)
     * @return 分配的节点ID，无分配返回 null
     */
    public String getNodeForSlot(int slot) {
        if (slot < 0 || slot >= ForumConstants.SLOT_COUNT) {
            throw new IllegalArgumentException("Invalid slot: " + slot);
        }

        return (String) redisTemplate.opsForHash()
                .get(ForumConstants.KEY_SLOT_ALLOCATION, String.valueOf(slot));
    }

    /**
     * 获取节点负责的所有槽位
     *
     * @param nodeId 节点ID
     * @return 槽位列表
     */
    public Set<Integer> getSlotsForNode(String nodeId) {
        Set<Integer> slots = new HashSet<>();

        Map<Object, Object> allocation = redisTemplate.opsForHash()
                .entries(ForumConstants.KEY_SLOT_ALLOCATION);

        for (Map.Entry<Object, Object> entry : allocation.entrySet()) {
            if (nodeId.equals(entry.getValue())) {
                try {
                    slots.add(Integer.parseInt(entry.getKey().toString()));
                } catch (NumberFormatException e) {
                    log.warn("Invalid slot number: {}", entry.getKey());
                }
            }
        }

        return slots;
    }

    /**
     * 获取所有槽位分配信息
     *
     * @return 槽位 -> 节点ID 的映射
     */
    public Map<Integer, String> getAllSlotAllocations() {
        Map<Integer, String> result = new HashMap<>();

        Map<Object, Object> allocation = redisTemplate.opsForHash()
                .entries(ForumConstants.KEY_SLOT_ALLOCATION);

        for (Map.Entry<Object, Object> entry : allocation.entrySet()) {
            try {
                int slot = Integer.parseInt(entry.getKey().toString());
                result.put(slot, (String) entry.getValue());
            } catch (NumberFormatException e) {
                log.warn("Invalid slot number: {}", entry.getKey());
            }
        }

        return result;
    }

    /**
     * 扩容：将部分槽位迁移到新节点
     * 策略：从负载最高的节点迁移部分槽位
     *
     * @param newNodeId     新节点ID
     * @param slotsToAssign 分配给新节点的槽位数量
     */
    public void expandNode(String newNodeId, int slotsToAssign) {
        log.info("Expanding with new node {}, assigning {} slots", newNodeId, slotsToAssign);

        // 获取当前所有节点及其槽位
        Map<String, Set<Integer>> nodeSlots = getNodeSlotMapping();

        if (nodeSlots.isEmpty()) {
            // 首次扩容，初始化分配
            initializeSlotAllocation(Collections.singletonList(newNodeId));
            return;
        }

        // 找出负载最高的节点（槽位最多）
        String highestLoadNode = nodeSlots.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElseThrow(() -> new ForumRoutingException("No existing nodes found"));

        Set<Integer> sourceSlots = nodeSlots.get(highestLoadNode);

        if (sourceSlots.size() <= slotsToAssign) {
            throw new ForumRoutingException("Source node " + highestLoadNode +
                    " doesn't have enough slots to migrate");
        }

        // 选择要迁移的槽位（取前 N 个）
        List<Integer> slotsToMigrate = sourceSlots.stream()
                .sorted()
                .limit(slotsToAssign)
                .collect(Collectors.toList());

        // 执行迁移
        migrateSlots(slotsToMigrate, newNodeId);

        log.info("Migrated {} slots from {} to {}",
                slotsToMigrate.size(), highestLoadNode, newNodeId);
    }

    /**
     * 缩容：将节点的槽位迁移到其他节点
     *
     * @param nodeIdToRemove 要移除的节点ID
     * @param targetNodeIds  目标节点ID列表（接收迁移的槽位）
     */
    public void shrinkNode(String nodeIdToRemove, List<String> targetNodeIds) {
        log.info("Shrinking node {}, migrating slots to {}", nodeIdToRemove, targetNodeIds);

        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            throw new IllegalArgumentException("Target node list cannot be empty");
        }

        Set<Integer> slotsToMigrate = getSlotsForNode(nodeIdToRemove);

        if (slotsToMigrate.isEmpty()) {
            log.warn("Node {} has no slots to migrate", nodeIdToRemove);
            return;
        }

        // 平均分配给目标节点
        int targetCount = targetNodeIds.size();
        int slotsPerTarget = slotsToMigrate.size() / targetCount;
        int remainder = slotsToMigrate.size() % targetCount;

        List<Integer> slotList = new ArrayList<>(slotsToMigrate);
        Collections.sort(slotList);

        int index = 0;
        for (int i = 0; i < targetCount; i++) {
            String targetNode = targetNodeIds.get(i);
            int slotsForThisTarget = slotsPerTarget + (i < remainder ? 1 : 0);

            List<Integer> targetSlots = slotList.subList(index, index + slotsForThisTarget);
            migrateSlots(targetSlots, targetNode);

            log.info("Migrated {} slots to {}", targetSlots.size(), targetNode);
            index += slotsForThisTarget;
        }

        log.info("Completed shrinking node {}, migrated {} slots",
                nodeIdToRemove, slotsToMigrate.size());
    }

    /**
     * 迁移指定槽位到目标节点
     *
     * @param slots      要迁移的槽位列表
     * @param targetNode 目标节点ID
     */
    public void migrateSlots(List<Integer> slots, String targetNode) {
        for (Integer slot : slots) {
            redisTemplate.opsForHash().put(
                    ForumConstants.KEY_SLOT_ALLOCATION,
                    String.valueOf(slot),
                    targetNode
            );
        }
        log.debug("Migrated slots {} to {}", slots, targetNode);
    }

    /**
     * 紧急故障转移：将死亡节点的槽位立即迁移
     * 用于节点宕机时的紧急处理
     *
     * @param deadNodeId     死亡节点ID
     * @param healthyNodeIds 健康节点列表
     */
    public void emergencyMigrate(String deadNodeId, List<String> healthyNodeIds) {
        log.warn("Emergency migration for dead node {} to {}", deadNodeId, healthyNodeIds);

        Set<Integer> deadSlots = getSlotsForNode(deadNodeId);

        if (deadSlots.isEmpty()) {
            log.warn("Dead node {} has no slots", deadNodeId);
            return;
        }

        if (healthyNodeIds.isEmpty()) {
            throw new ForumRoutingException("No healthy nodes available for emergency migration");
        }

        // 轮询分配给健康节点
        int index = 0;
        for (Integer slot : deadSlots) {
            String targetNode = healthyNodeIds.get(index % healthyNodeIds.size());
            redisTemplate.opsForHash().put(
                    ForumConstants.KEY_SLOT_ALLOCATION,
                    String.valueOf(slot),
                    targetNode
            );
            index++;
        }

        log.info("Emergency migrated {} slots from {} to {} nodes",
                deadSlots.size(), deadNodeId, healthyNodeIds.size());
    }

    /**
     * 获取节点槽位映射
     *
     * @return 节点ID -> 槽位集合 的映射
     */
    public Map<String, Set<Integer>> getNodeSlotMapping() {
        Map<String, Set<Integer>> mapping = new HashMap<>();

        Map<Integer, String> allocation = getAllSlotAllocations();

        for (Map.Entry<Integer, String> entry : allocation.entrySet()) {
            mapping.computeIfAbsent(entry.getValue(), k -> new HashSet<>())
                    .add(entry.getKey());
        }

        return mapping;
    }

    /**
     * 获取当前槽位分配统计
     *
     * @return 统计信息字符串
     */
    public String getAllocationStats() {
        Map<String, Set<Integer>> mapping = getNodeSlotMapping();

        StringBuilder sb = new StringBuilder("Slot Allocation Stats:\n");
        for (Map.Entry<String, Set<Integer>> entry : mapping.entrySet()) {
            sb.append(String.format("  %s: %d slots%n", entry.getKey(), entry.getValue().size()));
        }

        return sb.toString();
    }

    /**
     * 验证槽位分配完整性
     * 检查是否所有 1024 个槽位都有分配
     *
     * @return true if valid
     */
    public boolean validateAllocation() {
        Map<Integer, String> allocation = getAllSlotAllocations();

        // 检查数量
        if (allocation.size() != ForumConstants.SLOT_COUNT) {
            log.warn("Slot allocation incomplete: {}/{} slots assigned",
                    allocation.size(), ForumConstants.SLOT_COUNT);
            return false;
        }

        // 检查范围
        for (int i = 0; i < ForumConstants.SLOT_COUNT; i++) {
            if (!allocation.containsKey(i)) {
                log.warn("Slot {} is not assigned", i);
                return false;
            }
        }

        return true;
    }
}
