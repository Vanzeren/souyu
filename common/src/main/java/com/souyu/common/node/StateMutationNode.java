package com.souyu.common.node;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.state.State;

import java.util.Map;

/**
 * 状态变更节点抽象类
 * @param <T> 输入数据类型
 * @param <R> run方法的返回类型
 */
public abstract class StateMutationNode<T, R> extends AbstractNode<T, R> {

    public StateMutationNode(TimeContextChatClient chatClient, String nodeName) {
        super(chatClient, nodeName);
    }

    /**
     * 修改状态
     *
     * @param inputData 输入数据
     * @param state     当前状态
     * @param kwargs    额外参数
     * @return 修改后的状态
     */
    public abstract State mutateState(T inputData, State state, Map<String, Object> kwargs);
}
