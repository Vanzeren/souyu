package com.souyu.reportengine.nodes;

import com.souyu.common.client.TimeContextChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 节点基类。
 * <p>
 * 统一实现日志工具、输入/输出钩子以及LLM客户端依赖注入，
 * 便于所有节点只专注业务逻辑。
 */
public abstract class BaseNode<I, O> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    @Autowired
    protected TimeContextChatClient llmClient;
    protected final String nodeName;

    /**
     * 初始化节点
     *
     * @param nodeName  节点名称
     */
    public BaseNode( String nodeName) {
        this.nodeName = (nodeName != null && !nodeName.isEmpty()) ? nodeName : getClass().getSimpleName();
    }

    /**
     * 执行节点处理逻辑
     *
     * @param inputData 输入数据
     * @return 处理结果
     */
    public abstract O run(I inputData);

    /**
     * 验证输入数据。
     * 默认直接通过，子类可按需覆写实现字段检查。
     *
     * @param inputData 输入数据
     * @return 验证是否通过
     */
    public boolean validateInput(I inputData) {
        return true;
    }

    /**
     * 处理输出数据。
     * 子类可覆写进行结构化或校验。
     *
     * @param output 原始输出
     * @return 处理后的输出
     */
    public O processOutput(O output) {
        return output;
    }

    /**
     * 记录信息日志，并自动带上节点名作为前缀。
     *
     * @param message 日志消息
     */
    public void logInfo(String message) {
        logger.info("[{}] {}", nodeName, message);
    }

    /**
     * 记录错误日志，便于排障。
     *
     * @param message 日志消息
     */
    public void logError(String message) {
        logger.error("[{}] {}", nodeName, message);
    }
}
