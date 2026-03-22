package com.souyu.forum.engine;

import com.souyu.common.forum.ForumConstants;
import com.souyu.common.forum.ForumSlotManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Forum 节点生命周期管理
 * 负责节点注册、心跳维护和优雅下线
 */
@Component
@ConditionalOnProperty(name = "forum.node.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ForumNodeLifecycle {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${server.host:}")
    private String configuredHost;

    @Value("${server.port:8083}")
    private int port;

    @Value("${forum.node.capacity:20}")
    private int capacity;

    @Value("${forum.node.id:}")
    private String configuredNodeId;

    private String nodeId;
    private String host;
    private volatile boolean running = true;
    private ScheduledExecutorService heartbeatExecutor;

    @Autowired
    private ForumSlotManager slotManager;

    @PostConstruct
    public void init() {
        // 确定 host
        this.host = resolveHost();

        // 生成唯一节点ID
        this.nodeId = generateNodeId();

        // 注册节点
        registerNode();

        // 启动心跳
        startHeartbeat();

        // 初始化槽位分配（如果是第一个节点）
        initializeSlotsIfNeeded();

        log.info("Forum node {} started at {}:{}, capacity: {}",
                nodeId, host, port, capacity);
    }

    /**
     * 如果槽位分配未初始化，则进行初始化
     */
    private void initializeSlotsIfNeeded() {
        try {
            // 检查是否已有槽位分配
            Map<Integer, String> allocations = slotManager.getAllSlotAllocations();
            if (allocations.isEmpty()) {
                log.info("Slot allocation not initialized, initializing...");
                // 获取所有健康节点（包括自己）
                List<String> healthyNodes = new ArrayList<>();
                healthyNodes.add(nodeId);
                slotManager.initializeSlotAllocation(healthyNodes);
                log.info("Slot allocation initialized for node: {}", nodeId);
            } else {
                log.info("Slot allocation already initialized, {} slots assigned", allocations.size());
            }
        } catch (Exception e) {
            log.warn("Failed to initialize slots: {}", e.getMessage());
        }
    }

    /**
     * 解析主机地址
     */
    private String resolveHost() {
        if (configuredHost != null && !configuredHost.isEmpty()) {
            return configuredHost;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Failed to get local host address, using localhost", e);
            return "localhost";
        }
    }

    /**
     * 生成唯一节点ID
     * 格式: forum-{hostHash}-{port}
     */
    private String generateNodeId() {
        if (configuredNodeId != null && !configuredNodeId.isEmpty()) {
            return configuredNodeId;
        }
        // 使用 host 和 port 生成唯一ID
        String hostPart = Integer.toHexString(host.hashCode());
        return String.format("forum-%s-%d", hostPart, port);
    }

    /**
     * 注册节点到 Redis
     */
    private void registerNode() {
        String key = ForumConstants.KEY_NODE_PREFIX + nodeId;

        Map<String, String> nodeInfo = new HashMap<>();
        nodeInfo.put("nodeId", nodeId);
        nodeInfo.put("host", host);
        nodeInfo.put("port", String.valueOf(port));
        nodeInfo.put("load", "0");
        nodeInfo.put("capacity", String.valueOf(capacity));
        nodeInfo.put("region", "default");
        nodeInfo.put("lastHeartbeat", String.valueOf(System.currentTimeMillis()));

        redisTemplate.opsForHash().putAll(key, nodeInfo);
        redisTemplate.expire(key, ForumConstants.NODE_TTL_SECONDS, TimeUnit.SECONDS);

        // 初始化节点任务集合
        String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, nodeId);
        redisTemplate.delete(tasksKey);

        log.info("Registered forum node: {}", nodeId);
    }

    /**
     * 启动心跳线程
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "forum-heartbeat-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!running) {
                    return;
                }

                String key = ForumConstants.KEY_NODE_PREFIX + nodeId;

                // 检查节点 key 是否存在（可能被故障转移清理）
                Boolean exists = redisTemplate.hasKey(key);
                if (!Boolean.TRUE.equals(exists)) {
                    log.warn("Node key disappeared, re-registering...");
                    registerNode();
                    return;
                }

                // 更新心跳时间
                redisTemplate.opsForHash().put(key, "lastHeartbeat",
                        String.valueOf(System.currentTimeMillis()));

                // 刷新负载信息
                updateLoadInfo();

                // 续租 TTL
                redisTemplate.expire(key, ForumConstants.NODE_TTL_SECONDS, TimeUnit.SECONDS);

                log.debug("Heartbeat sent for node {}", nodeId);

            } catch (Exception e) {
                log.error("Failed to send heartbeat", e);
            }
        }, 5, 5, TimeUnit.SECONDS);  // 每5秒心跳，TTL 15秒（3个周期）
    }

    /**
     * 更新负载信息
     */
    private void updateLoadInfo() {
        try {
            String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, nodeId);
            Long taskCount = redisTemplate.opsForSet().size(tasksKey);

            String key = ForumConstants.KEY_NODE_PREFIX + nodeId;
            redisTemplate.opsForHash().put(key, "load",
                    String.valueOf(taskCount != null ? taskCount : 0));
        } catch (Exception e) {
            log.warn("Failed to update load info", e);
        }
    }

    /**
     * 获取当前节点ID
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取当前节点主机
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取当前节点端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取当前负载
     */
    public int getCurrentLoad() {
        try {
            String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, nodeId);
            Long count = redisTemplate.opsForSet().size(tasksKey);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            log.warn("Failed to get current load", e);
            return 0;
        }
    }

    /**
     * 获取容量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 是否有可用容量
     */
    public boolean hasCapacity() {
        return getCurrentLoad() < capacity;
    }

    /**
     * 优雅下线
     */
    @PreDestroy
    public void destroy() {
        running = false;
        log.info("Shutting down forum node {}...", nodeId);

        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 清理节点数据
        try {
            String key = ForumConstants.KEY_NODE_PREFIX + nodeId;
            redisTemplate.delete(key);

            String tasksKey = String.format(ForumConstants.KEY_NODE_TASKS, nodeId);
            redisTemplate.delete(tasksKey);

            log.info("Forum node {} stopped and cleaned up", nodeId);
        } catch (Exception e) {
            log.error("Failed to cleanup node data", e);
        }
    }
}
