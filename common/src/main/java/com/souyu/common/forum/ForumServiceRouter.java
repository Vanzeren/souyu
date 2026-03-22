package com.souyu.common.forum;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Forum 服务路由器
 * 负责将任务路由到正确的 Forum 节点
 * 使用固定槽位算法保证同一任务路由到同一节点
 */
@Service
@Slf4j
public class ForumServiceRouter {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ForumSlotManager slotManager;

    private DefaultRedisScript<String> bindScript;

    @PostConstruct
    public void init() {
        bindScript = new DefaultRedisScript<>(ForumConstants.LUA_ATOMIC_BIND, String.class);
    }

    /**
     * 计算任务对应的槽位 (0-1023)
     * 相同 taskId 永远得到相同 slot
     *
     * @param taskId 任务ID
     * @return 槽位号 0-1023
     */
    public int getSlot(String taskId) {
        return Math.abs(taskId.hashCode()) % ForumConstants.SLOT_COUNT;
    }

    /**
     * 获取或创建任务绑定
     * 线程安全：使用 Lua 脚本原子操作
     *
     * @param taskId 任务ID
     * @return 绑定的节点信息
     * @throws ForumRoutingException 当路由失败时抛出
     */
    public ForumNode getOrBindNode(String taskId) {
        String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);

        // 1. 尝试获取现有绑定
        Map<Object, Object> binding = redisTemplate.opsForHash().entries(bindingKey);

        if (!binding.isEmpty()) {
            String nodeId = (String) binding.get("nodeId");
            String status = (String) binding.get("status");

            // 检查绑定是否有效且节点存活
            if ("ACTIVE".equals(status) && isNodeAlive(nodeId)) {
                // 续租 TTL
                redisTemplate.expire(bindingKey, ForumConstants.BINDING_TTL_MINUTES, TimeUnit.MINUTES);
                log.debug("Task {} binding refreshed, node: {}", taskId, nodeId);
                return buildNodeFromBinding(binding);
            }

            log.warn("Task {} binding invalid or node {} dead, re-routing", taskId, nodeId);
        }

        // 2. 重新路由到新节点
        return routeToNewNode(taskId);
    }

    /**
     * 路由到新节点
     * 1. 计算槽位
     * 2. 查询槽位分配表（如果为空则自动初始化）
     * 3. 检查节点容量
     * 4. 原子绑定
     *
     * @param taskId 任务ID
     * @return 路由到的节点
     */
    private ForumNode routeToNewNode(String taskId) {
        int slot = getSlot(taskId);
        log.info("Routing task {} to new node, slot: {}", taskId, slot);

        // 查询槽位分配
        String nodeId = (String) redisTemplate.opsForHash()
                .get(ForumConstants.KEY_SLOT_ALLOCATION, String.valueOf(slot));

        // 【关键】如果槽位未分配，尝试自动初始化
        if (nodeId == null) {
            log.warn("No node assigned for slot: {}, attempting to initialize slot allocation", slot);

            // 尝试从健康节点初始化槽位分配
            if (tryInitializeSlotAllocation()) {
                // 重新查询槽位分配
                nodeId = (String) redisTemplate.opsForHash()
                        .get(ForumConstants.KEY_SLOT_ALLOCATION, String.valueOf(slot));
            }

            if (nodeId == null) {
                log.error("No node assigned for slot: {} after initialization attempt", slot);
                throw new ForumRoutingException("No node assigned for slot: " + slot);
            }
        }

        // 检查节点是否存活
        if (!isNodeAlive(nodeId)) {
            log.warn("Assigned node {} is dead, finding alternative", nodeId);
            nodeId = findAlternativeNode(slot);
        }

        // 检查节点容量
        if (!checkNodeCapacity(nodeId)) {
            log.warn("Node {} is overloaded, finding alternative", nodeId);
            nodeId = findAlternativeNode(slot);
        }

        // 获取节点信息
        ForumNode node = getNodeInfo(nodeId);
        if (node == null) {
            throw new ForumRoutingException("Cannot get info for node: " + nodeId);
        }

        // 原子绑定
        return atomicBind(taskId, node);
    }

    /**
     * 原子绑定任务到节点
     * 使用 Lua 脚本保证线程安全
     *
     * @param taskId 任务ID
     * @param node   目标节点
     * @return 绑定后的节点信息
     */
    private ForumNode atomicBind(String taskId, ForumNode node) {
        String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);

        try {
            String result = redisTemplate.execute(
                    bindScript,
                    Arrays.asList(bindingKey, taskId),
                    node.getNodeId(),
                    String.valueOf(ForumConstants.BINDING_TTL_MINUTES * 60), // 转为秒
                    node.getHost(),
                    String.valueOf(node.getPort()),
                    String.valueOf(System.currentTimeMillis())
            );

            if (result == null) {
                throw new ForumRoutingException("Lua script returned null for task: " + taskId);
            }

            log.info("Task {} bound to node {} (slot: {})", taskId, result, getSlot(taskId));
            return node;

        } catch (Exception e) {
            log.error("Failed to bind task {} to node {}", taskId, node.getNodeId(), e);
            throw new ForumRoutingException("Failed to bind task: " + taskId, e);
        }
    }

    /**
     * 检查节点是否存活
     *
     * @param nodeId 节点ID
     * @return true if alive
     */
    private boolean isNodeAlive(String nodeId) {
        String key = ForumConstants.KEY_NODE_PREFIX + nodeId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 检查节点容量
     *
     * @param nodeId 节点ID
     * @return true if has capacity
     */
    private boolean checkNodeCapacity(String nodeId) {
        String key = ForumConstants.KEY_NODE_PREFIX + nodeId;
        String loadStr = (String) redisTemplate.opsForHash().get(key, "load");
        String capacityStr = (String) redisTemplate.opsForHash().get(key, "capacity");

        if (loadStr == null || capacityStr == null) {
            return false;
        }

        try {
            int load = Integer.parseInt(loadStr);
            int capacity = Integer.parseInt(capacityStr);
            return load < capacity;
        } catch (NumberFormatException e) {
            log.warn("Invalid load/capacity format for node {}", nodeId);
            return false;
        }
    }

    /**
     * 获取节点信息
     *
     * @param nodeId 节点ID
     * @return 节点信息，不存在返回 null
     */
    private ForumNode getNodeInfo(String nodeId) {
        String key = ForumConstants.KEY_NODE_PREFIX + nodeId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        if (entries.isEmpty()) {
            return null;
        }

        ForumNode node = new ForumNode();
        node.setNodeId(nodeId);
        node.setHost((String) entries.get("host"));

        String portStr = (String) entries.get("port");
        if (portStr != null) {
            node.setPort(Integer.parseInt(portStr));
        }

        String loadStr = (String) entries.get("load");
        if (loadStr != null) {
            node.setLoad(Integer.parseInt(loadStr));
        }

        String capacityStr = (String) entries.get("capacity");
        if (capacityStr != null) {
            node.setCapacity(Integer.parseInt(capacityStr));
        }

        node.setRegion((String) entries.get("region"));

        String heartbeatStr = (String) entries.get("lastHeartbeat");
        if (heartbeatStr != null) {
            node.setLastHeartbeat(Long.parseLong(heartbeatStr));
        }

        return node;
    }

    /**
     * 从绑定信息构建节点对象
     *
     * @param binding 绑定 Hash
     * @return 节点信息
     */
    private ForumNode buildNodeFromBinding(Map<Object, Object> binding) {
        ForumNode node = new ForumNode();
        node.setNodeId((String) binding.get("nodeId"));
        node.setHost((String) binding.get("host"));

        String portStr = (String) binding.get("port");
        if (portStr != null) {
            node.setPort(Integer.parseInt(portStr));
        }

        return node;
    }

    /**
     * 寻找备选节点
     * 当指定节点不可用时，选择负载最低的健康节点
     *
     * @param originalSlot 原始槽位
     * @return 备选节点ID
     * @throws ForumRoutingException 当没有可用节点时抛出
     */
    private String findAlternativeNode(int originalSlot) {
        // 获取所有健康节点
        Set<String> nodeKeys = redisTemplate.keys(ForumConstants.KEY_NODE_PREFIX + "*");

        if (nodeKeys == null || nodeKeys.isEmpty()) {
            throw new ForumRoutingException("No healthy forum nodes available");
        }

        String bestNode = null;
        double bestLoadRatio = 1.0;

        for (String key : nodeKeys) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries.isEmpty()) {
                continue;
            }

            String nodeId = (String) entries.get("nodeId");
            if (nodeId == null) {
                // 从 key 中提取 nodeId
                nodeId = key.substring(ForumConstants.KEY_NODE_PREFIX.length());
            }

            String loadStr = (String) entries.get("load");
            String capacityStr = (String) entries.get("capacity");

            if (loadStr == null || capacityStr == null) {
                continue;
            }

            try {
                int load = Integer.parseInt(loadStr);
                int capacity = Integer.parseInt(capacityStr);

                if (capacity > 0 && load < capacity) {
                    double loadRatio = (double) load / capacity;
                    if (loadRatio < bestLoadRatio) {
                        bestLoadRatio = loadRatio;
                        bestNode = nodeId;
                    }
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid number format for node: {}", nodeId);
            }
        }

        if (bestNode == null) {
            throw new ForumRoutingException("No forum node with available capacity");
        }

        log.info("Found alternative node {} for slot {} (load ratio: {})",
                bestNode, originalSlot, bestLoadRatio);
        return bestNode;
    }

    /**
     * 尝试初始化槽位分配
     * 当槽位分配表为空时，从所有健康节点初始化
     *
     * @return true if initialization successful
     */
    private synchronized boolean tryInitializeSlotAllocation() {
        try {
            // 再次检查是否已经有槽位分配（双重检查）
            Long slotCount = redisTemplate.opsForHash().size(ForumConstants.KEY_SLOT_ALLOCATION);
            if (slotCount != null && slotCount > 0) {
                log.debug("Slot allocation already exists with {} slots", slotCount);
                return true;
            }

            // 获取所有健康节点
            Set<String> nodeKeys = redisTemplate.keys(ForumConstants.KEY_NODE_PREFIX + "*");
            if (nodeKeys == null || nodeKeys.isEmpty()) {
                log.warn("No healthy forum nodes found for slot initialization");
                return false;
            }

            List<String> healthyNodes = new ArrayList<>();
            for (String key : nodeKeys) {
                String nodeId = key.substring(ForumConstants.KEY_NODE_PREFIX.length());
                if (isNodeAlive(nodeId)) {
                    healthyNodes.add(nodeId);
                }
            }

            if (healthyNodes.isEmpty()) {
                log.warn("No alive forum nodes found for slot initialization");
                return false;
            }

            // 初始化槽位分配
            log.info("Initializing slot allocation with {} healthy nodes: {}",
                    healthyNodes.size(), healthyNodes);
            slotManager.initializeSlotAllocation(healthyNodes);

            return true;

        } catch (Exception e) {
            log.error("Failed to initialize slot allocation", e);
            return false;
        }
    }

    /**
     * 清除任务绑定（用于故障转移后）
     *
     * @param taskId 任务ID
     */
    public void clearBinding(String taskId) {
        String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);
        redisTemplate.delete(bindingKey);
        log.info("Cleared binding for task {}", taskId);
    }

    /**
     * 获取任务当前绑定的节点（不创建新绑定）
     *
     * @param taskId 任务ID
     * @return 绑定的节点ID，无绑定返回 null
     */
    public String getBoundNodeId(String taskId) {
        String bindingKey = String.format(ForumConstants.KEY_TASK_BINDING, taskId);
        return (String) redisTemplate.opsForHash().get(bindingKey, "nodeId");
    }
}
