package com.souyu.common.forum;

import lombok.Data;

import java.io.Serializable;

/**
 * Forum 节点信息 DTO
 * 存储节点元数据用于路由决策
 */
@Data
public class ForumNode implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 节点唯一标识 (如: forum-abc123-8083)
     */
    private String nodeId;

    /**
     * 节点主机地址
     */
    private String host;

    /**
     * 节点端口
     */
    private int port;

    /**
     * 当前任务数 (负载)
     */
    private int load;

    /**
     * 最大容量
     */
    private int capacity;

    /**
     * 区域 (用于多区域部署)
     */
    private String region;

    /**
     * 最后心跳时间戳
     */
    private long lastHeartbeat;

    /**
     * 检查节点是否有可用容量
     */
    public boolean hasCapacity() {
        return load < capacity;
    }

    /**
     * 获取负载率 (0.0 - 1.0)
     */
    public double getLoadRatio() {
        if (capacity <= 0) {
            return 1.0;
        }
        return (double) load / capacity;
    }

    /**
     * 构建 Stream Key
     */
    public String getStreamKey() {
        return ForumConstants.KEY_STREAM_PREFIX + nodeId;
    }

    /**
     * 构建节点信息 Key
     */
    public String getNodeKey() {
        return ForumConstants.KEY_NODE_PREFIX + nodeId;
    }
}
