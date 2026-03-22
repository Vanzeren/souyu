package com.souyu.common.forum;

/**
 * Forum 路由异常
 * 当消息路由失败时抛出
 */
public class ForumRoutingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForumRoutingException(String message) {
        super(message);
    }

    public ForumRoutingException(String message, Throwable cause) {
        super(message, cause);
    }

    public ForumRoutingException(Throwable cause) {
        super(cause);
    }
}
