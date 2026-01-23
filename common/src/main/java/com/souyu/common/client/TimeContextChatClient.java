package com.souyu.common.client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Media;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 一个“智能”的 ChatClient 包装器，提供了两种额外的价值：
 * 1. 自动为所有请求的 Prompt 添加当前时间戳。
 * 2. 提供一个便捷的 streamAndCollect 方法，用于流式调用并一次性返回完整字符串。
 */
@Component
public class TimeContextChatClient {

    private final ChatClient.Builder chatClientBuilder;

    @Autowired
    public TimeContextChatClient(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public ChatResponse call(Prompt prompt) {
        Prompt timedPrompt = addTimeToPrompt(prompt);
        return chatClientBuilder.build().prompt(timedPrompt).call().chatResponse();
    }

    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt timedPrompt =
                addTimeToPrompt(prompt);
        return chatClientBuilder.build().prompt(timedPrompt).stream().chatResponse();
    }

    public Mono<String> streamAndCollect(Prompt prompt) {
        return this.stream(prompt)
                .map(response -> {
                    if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                        return response.getResult().getOutput().getContent();
                    }
                    return "";
                })
                .collect(Collectors.joining());
    }

    private Prompt addTimeToPrompt(Prompt originalPrompt) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日HH时mm分");
        String timePrefix = "今天的实际时间是" + LocalDateTime.now().format(formatter);

        List<Message> originalMessages = new ArrayList<>(originalPrompt.getInstructions());
        boolean timeAdded = false;
        for (int i = 0; i < originalMessages.size(); i++) {
            Message currentMessage = originalMessages.get(i);
            if (currentMessage instanceof UserMessage userMessage) {
                String newContent = timePrefix + "\n" + userMessage.getContent();
                // FIX: Convert Collection<Media> to List<Media> for the constructor
                List<Media> mediaList = new ArrayList<>(userMessage.getMedia());
                originalMessages.set(i, new UserMessage(newContent, mediaList));
                timeAdded = true;
                break;
            }
        }

        if (timeAdded) {
            // FIX: Cast ModelOptions to ChatOptions for the constructor
            ChatOptions chatOptions = null;
            if (originalPrompt.getOptions() instanceof ChatOptions) {
                chatOptions = (ChatOptions) originalPrompt.getOptions();
            }
            return new Prompt(originalMessages, chatOptions);
        }
        return originalPrompt;
    }
}
