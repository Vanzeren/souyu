package com.souyu.orchestrator.monitor;

import com.souyu.common.forum.ForumConstants;
import com.souyu.common.forum.ForumFailoverManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Forum-Engine 健康检查器
 * 作为故障检测的主要入口，定期扫描所有 Forum-Engine 节点
 *
 * 注意：这是心跳检测的第二层（兜底机制）
 * 第一层是 Redis Key 过期事件（如果开启）
 */
@Component
@EnableScheduling
@Slf4j
public class ForumHealthChecker {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ForumFailoverManager failoverManager;

    @Value("${forum.health-check.interval-ms:10000}")
    private long checkIntervalMs;

    @Value("${forum.health-check.enabled:true}")
    private boolean enabled;

    // 心跳超时阈值（毫秒）
    // TTL 是 15s，这里给 20s 的宽容度
    private static final long HEARTBEAT_TIMEOUT_MS = 20000;

    /**
     * 定期扫描所有 Forum-Engine 节点
     */
    @Scheduled(fixedDelayString = "${forum.health-check.interval-ms:10000}")
    public void scanForumNodes() {
        if (!enabled) {
            return;
        }

        Set<String> nodeKeys = redisTemplate.keys(ForumConstants.KEY_NODE_PREFIX + "*");

        if (nodeKeys == null || nodeKeys.isEmpty()) {
            log.debug("No forum nodes found");
            return;
        }

        log.debug("Scanning {} forum nodes", nodeKeys.size());

        for (String key : nodeKeys) {
            checkNode(key);
        }
    }

    /**
     * 检查单个节点健康状态
     */
    private void checkNode(String nodeKey) {
        String nodeId = nodeKey.substring(ForumConstants.KEY_NODE_PREFIX.length());

        try {
            // 检查 1：Key 是否存在
            Boolean exists = redisTemplate.hasKey(nodeKey);
            if (!Boolean.TRUE.equals(exists)) {
                log.warn("Node {} key missing, triggering failover", nodeId);
                triggerFailover(nodeId);
                return;
            }

            // 检查 2：lastHeartbeat 时间戳
            String heartbeatStr = (String) redisTemplate.opsForHash()
                    .get(nodeKey, "lastHeartbeat");

            if (heartbeatStr == null) {
                log.warn("Node {} has no heartbeat field", nodeId);
                triggerFailover(nodeId);
                return;
            }

            long lastHeartbeat = Long.parseLong(heartbeatStr);
            long elapsed = System.currentTimeMillis() - lastHeartbeat;

            if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                log.warn("Node {} heartbeat timeout: {}ms, triggering failover", nodeId, elapsed);
                triggerFailover(nodeId);
            }

        } catch (NumberFormatException e) {
            log.error("Invalid heartbeat format for node: {}", nodeId);
            triggerFailover(nodeId);
        } catch (Exception e) {
            log.error("Error checking node {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * 触发故障转移
     */
    private void triggerFailover(String nodeId) {
        try {
            // 加分布式锁防止重复故障转移
            String lockKey = "forum:failover:lock:" + nodeId;
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 60, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(locked)) {
                log.debug("Failover already in progress for {}, skip", nodeId);
                return;
            }

            // 双重检查：确认节点确实有问题
            // 防止在获取锁期间节点恢复了
            Boolean exists = redisTemplate.hasKey(ForumConstants.KEY_NODE_PREFIX + nodeId);
            if (Boolean.TRUE.equals(exists)) {
                String heartbeat = (String) redisTemplate.opsForHash()
                        .get(ForumConstants.KEY_NODE_PREFIX + nodeId, "lastHeartbeat");
                if (heartbeat != null) {
                    long last = Long.parseLong(heartbeat);
                    if (System.currentTimeMillis() - last < HEARTBEAT_TIMEOUT_MS) {
                        log.info("Node {} recovered during check, skip failover", nodeId);
                        redisTemplate.delete(lockKey);
                        return;
                    }
                }
            }

            log.info("Executing failover for node: {}", nodeId);
            failoverManager.performFailover(nodeId);

            // 释放锁
            redisTemplate.delete(lockKey);

        } catch (Exception e) {
            log.error("Failover failed for node {}: {}", nodeId, e.getMessage());
        }
    }
}
