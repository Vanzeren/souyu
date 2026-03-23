package com.souyu.common.forum;

/**
 * Forum 消息类型枚举
 */
public enum MessageType {
    /**
     * 内容消息 - 正常的日志/输出内容
     */
    CONTENT,

    /**
     * 状态消息 - 状态更新
     */
    STATUS,

    /**
     * 错误消息 - 处理出错
     */
    ERROR,

    /**
     * 完成消息 - 任务完成通知
     */
    COMPLETE,

    /**
     * 最终检查指令 - 来自 Orchestrator 的总结请求
     */
    FINAL_CHECK
}
