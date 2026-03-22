package com.souyu.common.forum;

/**
 * Forum-Engine 常量定义
 */
public final class ForumConstants {

    private ForumConstants() {
        // 禁止实例化
    }

    // ==================== 槽位配置 ====================

    /**
     * 固定槽位数量 (0-1023)
     */
    public static final int SLOT_COUNT = 1024;

    /**
     * 每个槽位的虚拟子槽数量 (用于内部负载均衡)
     */
    public static final int SLOT_VIRTUAL_MULTIPLIER = 10;

    // ==================== TTL 配置 ====================

    /**
     * 节点心跳 TTL (秒)
     * 3个心跳周期 (5秒 * 3)
     */
    public static final int NODE_TTL_SECONDS = 15;

    /**
     * 任务绑定 TTL (分钟)
     * 活跃期间自动续租
     */
    public static final int BINDING_TTL_MINUTES = 30;

    // ==================== Redis Key 前缀 ====================

    /**
     * 节点信息前缀
     * 完整格式: forum:node:{nodeId}
     */
    public static final String KEY_NODE_PREFIX = "forum:node:";

    /**
     * 任务绑定 Key 格式
     * 完整格式: task:{taskId}:forum
     */
    public static final String KEY_TASK_BINDING = "task:%s:forum";

    /**
     * Stream Key 前缀
     * 完整格式: forum:stream:{nodeId}
     */
    public static final String KEY_STREAM_PREFIX = "forum:stream:";

    /**
     * 槽位分配表 Key
     */
    public static final String KEY_SLOT_ALLOCATION = "forum:slot:allocation";

    /**
     * 节点任务集合 Key 格式
     * 完整格式: forum:tasks:{nodeId}
     */
    public static final String KEY_NODE_TASKS = "forum:tasks:%s";

    /**
     * 消费者组名称
     */
    public static final String CONSUMER_GROUP = "forum-consumers";

    // ==================== 默认配置值 ====================

    /**
     * 默认节点容量
     */
    public static final int DEFAULT_NODE_CAPACITY = 20;

    /**
     * Stream 最大长度 (用于限流保护)
     */
    public static final long MAX_STREAM_LENGTH = 10000;

    /**
     * 消费者阻塞读取超时 (毫秒)
     */
    public static final long BLOCK_TIMEOUT_MS = 5000;

    /**
     * 任务锁等待时间 (秒)
     */
    public static final long TASK_LOCK_WAIT_SECONDS = 5;

    /**
     * 任务锁持有时间 (秒)
     */
    public static final long TASK_LOCK_HOLD_SECONDS = 60;

    // ==================== Lua 脚本 ====================

    /**
     * 原子绑定 Lua 脚本
     * KEYS[1]: 任务绑定 key
     * KEYS[2]: taskId
     * ARGV[1]: nodeId
     * ARGV[2]: TTL (秒)
     * ARGV[3]: host
     * ARGV[4]: port
     * ARGV[5]: assignedAt (时间戳)
     */
    public static final String LUA_ATOMIC_BIND =
            "local binding = redis.call('hget', KEYS[1], 'nodeId'); " +
            "if binding and redis.call('exists', 'forum:node:' .. binding) == 1 then " +
            "    redis.call('expire', KEYS[1], ARGV[2]); " +
            "    return binding; " +
            "end; " +
            "redis.call('hmset', KEYS[1], 'nodeId', ARGV[1], 'host', ARGV[3], 'port', ARGV[4], " +
            "'assignedAt', ARGV[5], 'status', 'ACTIVE'); " +
            "redis.call('expire', KEYS[1], ARGV[2]); " +
            "redis.call('sadd', 'forum:tasks:' .. ARGV[1], KEYS[2]); " +
            "return ARGV[1];";

    /**
     * 故障转移 Lua 脚本
     * ARGV[1]: 死亡节点 nodeId
     */
    public static final String LUA_FAILOVER =
            "local tasks = redis.call('smembers', 'forum:tasks:' .. ARGV[1]); " +
            "for i=1,#tasks do " +
            "    redis.call('hset', 'task:' .. tasks[i] .. ':forum', 'status', 'FAILED'); " +
            "end; " +
            "redis.call('del', 'forum:node:' .. ARGV[1]); " +
            "redis.call('del', 'forum:tasks:' .. ARGV[1]); " +
            "return #tasks;";
}
