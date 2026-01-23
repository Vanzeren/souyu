package com.souyu.common.node;

import com.souyu.common.client.TimeContextChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public abstract class AbstractNode<T, R> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractNode.class);
    protected final TimeContextChatClient chatClient;
    protected String nodeName;


    public AbstractNode(TimeContextChatClient chatClient, String nodeName) {
        this.chatClient = chatClient;
        if (nodeName != null) {
            this.nodeName = nodeName;
        }else{
            this.nodeName = this.getClass().getSimpleName();
        }
    }

    /**
     * 执行节点处理逻辑
     *
     * @param inputData 输入数据
     * @param kwargs    额外参数
     * @return 处理结果
     */
    public abstract R run(T inputData, Map<String, Object> kwargs);

    /**
     * 验证输入数据
     *
     * @param inputData 输入数据
     * @return 验证是否通过
     */
    public boolean validateInput(T inputData) {
        return true;
    }

    /**
     * 处理输出数据
     *
     * @param output 原始输出
     * @return 处理后的输出
     */
    public abstract  R processOutput(Object output);

    /**
     * 记录信息日志
     */
    public void logInfo(String message) {
        logger.info("[{}] {}", nodeName, message);
    }

    /**
     * 记录警告日志
     */
    public void logWarning(String message) {
        logger.warn("[{}] 警告: {}", nodeName, message);
    }

    /**
     * 记录错误日志
     */
    public void logError(String message) {
        logger.error("[{}] 错误: {}", nodeName, message);
    }
}
