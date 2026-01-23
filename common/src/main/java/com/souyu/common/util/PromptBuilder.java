package com.souyu.common.util;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个用于动态构建 Spring AI Prompt 对象的构建器.
 * <p>
 * 示例用法:
 * <pre>{@code
 * Prompt prompt = PromptBuilder.builder()
 *         .system("你是一个乐于助人的AI助手。")
 *         .user("告诉我一个关于龙的笑话。")
 *         .build();
 * }</pre>
 */
public class PromptBuilder {

    private final List<Message> messages;

    private PromptBuilder() {
        this.messages = new ArrayList<>();
    }

    /**
     * 创建一个新的 PromptBuilder 实例.
     *
     * @return 一个新的 PromptBuilder.
     */
    public static PromptBuilder builder() {
        return new PromptBuilder();
    }

    /**
     * 添加一个 SystemMessage.
     *
     * @param text 系统消息的内容.
     * @return 当前的 PromptBuilder 实例.
     */
    public PromptBuilder system(String text) {
        this.messages.add(new SystemMessage(text));
        return this;
    }

    /**
     * 添加一个 UserMessage.
     *
     * @param text 用户消息的内容.
     * @return 当前的 PromptBuilder 实例.
     */
    public PromptBuilder user(String text) {
        this.messages.add(new UserMessage(text));
        return this;
    }

    /**
     * 添加一个 AssistantMessage.
     *
     * @param text 助手消息的内容.
     * @return 当前的 PromptBuilder 实例.
     */
    public PromptBuilder assistant(String text) {
        this.messages.add(new AssistantMessage(text));
        return this;
    }

    /**
     * 添加一个已存在的 Message 对象.
     *
     * @param message 要添加的消息.
     * @return 当前的 PromptBuilder 实例.
     */
    public PromptBuilder message(Message message) {
        if (message != null) {
            this.messages.add(message);
        }
        return this;
    }

    /**
     * 根据已添加的消息构建最终的 Prompt 对象.
     *
     * @return 一个新的 Prompt 实例.
     */
    public Prompt build() {
        return new Prompt(new ArrayList<>(this.messages));
    }
}
