package com.souyu.common.client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 一个“智能”的 ChatClient 包装器，提供了两种额外的价值：
 * 1. 自动为所有请求的 Prompt 添加当前时间戳。
 * 2. 提供一个便捷的 streamAndCollect 方法，用于流式调用并一次性返回完整字符串。
 * 3. 暴露底层的 ChatClient.Builder 以支持 Function Calling。
 */
@Component
public class TimeContextChatClient {

    private final ChatClient.Builder chatClientBuilder;

    @Autowired
    public TimeContextChatClient(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }
    
    // 暴露 Builder 以便支持 functions() 调用
    public ChatClient.Builder getBuilder() {
        return this.chatClientBuilder;
    }

    public ChatResponse call(Prompt prompt) {
        Prompt timedPrompt = addTimeToPrompt(prompt);
        return chatClientBuilder.build().prompt(timedPrompt).call().chatResponse();
    }
    
    // 新增：支持 Function Calling 的调用
    public ChatResponse callWithFunctions(Prompt prompt, String... functionNames) {
        Prompt timedPrompt = addTimeToPrompt(prompt);
        
        // 使用 OpenAiChatOptions 来配置 functions
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder();
        
        // 如果原 Prompt 有 Options，尝试合并
        if (timedPrompt.getOptions() instanceof OpenAiChatOptions oldOptions) {
            if (oldOptions.getFunctions() != null) {
                oldOptions.getFunctions().forEach(optionsBuilder::withFunction);
            }
            if (oldOptions.getModel() != null) optionsBuilder.withModel(oldOptions.getModel());
            if (oldOptions.getTemperature() != null) optionsBuilder.withTemperature(oldOptions.getTemperature());
        }
        
        // 添加新的 functions
        for (String fn : functionNames) {
            optionsBuilder.withFunction(fn);
        }
        
        // 构建新的 Prompt，替换 Options
        Prompt promptWithFunctions = new Prompt(timedPrompt.getInstructions(), optionsBuilder.build());
        
        return chatClientBuilder.build()
                .prompt(promptWithFunctions)
                .call()
                .chatResponse();
    }

    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt timedPrompt = addTimeToPrompt(prompt);
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
                
                // 适配 UserMessage 构造函数
                // 显式获取 List<Media>，如果 getMedia 返回的是 Collection，则转换
                List<Media> mediaList = new ArrayList<>();
                if (userMessage.getMedia() != null) {
                    mediaList.addAll(userMessage.getMedia());
                }
                
                if (!mediaList.isEmpty()) {
                    originalMessages.set(i, new UserMessage(newContent, mediaList));
                } else {
                    originalMessages.set(i, new UserMessage(newContent));
                }
                
                timeAdded = true;
                break;
            }
        }

        if (timeAdded) {
            ChatOptions chatOptions = null;
            if (originalPrompt.getOptions() instanceof ChatOptions) {
                chatOptions = (ChatOptions) originalPrompt.getOptions();
            }
            return new Prompt(originalMessages, chatOptions);
        }
        return originalPrompt;
    }
}
