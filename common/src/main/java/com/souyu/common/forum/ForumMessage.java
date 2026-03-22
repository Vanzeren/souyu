package com.souyu.common.forum;

import lombok.Data;

import java.io.Serializable;

/**
 * Forum 消息 DTO
 * 用于 Engine 向 Forum-Engine 发送消息
 */
@Data
public class ForumMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 消息类型
     */
    private MessageType type;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 来源引擎 (query | media | report)
     */
    private String sourceEngine;

    /**
     * 时间戳 (毫秒)
     */
    private long timestamp;

    /**
     * 快速创建内容消息的工厂方法
     */
    public static ForumMessage content(String taskId, String content, String sourceEngine) {
        ForumMessage message = new ForumMessage();
        message.setTaskId(taskId);
        message.setType(MessageType.CONTENT);
        message.setContent(content);
        message.setSourceEngine(sourceEngine);
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }

    /**
     * 快速创建完成消息的工厂方法
     */
    public static ForumMessage complete(String taskId, String sourceEngine) {
        ForumMessage message = new ForumMessage();
        message.setTaskId(taskId);
        message.setType(MessageType.COMPLETE);
        message.setSourceEngine(sourceEngine);
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }

    /**
     * 快速创建错误消息的工厂方法
     */
    public static ForumMessage error(String taskId, String errorMessage, String sourceEngine) {
        ForumMessage message = new ForumMessage();
        message.setTaskId(taskId);
        message.setType(MessageType.ERROR);
        message.setContent(errorMessage);
        message.setSourceEngine(sourceEngine);
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }
}
