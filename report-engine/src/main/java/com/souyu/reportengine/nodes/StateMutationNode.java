package com.souyu.reportengine.nodes;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.reportengine.ReportState.ReportState;

/**
 * 带状态修改功能的节点基类。
 * <p>
 * 适用于节点需要直接写入 ReportState 的场景。
 */
public abstract class StateMutationNode<I> extends BaseNode<I, ReportState> {

    public StateMutationNode(String nodeName) {
        super( nodeName);
    }

    /**
     * 修改状态。
     * <p>
     * 子类需返回新的状态对象或在原地修改后回传，供流水线记录。
     *
     * @param inputData 输入数据
     * @param state     当前状态
     * @return 修改后的状态
     */
    public abstract ReportState mutateState(I inputData, ReportState state);

    /**
     * 默认的 run 实现，通常 StateMutationNode 不直接调用 run，而是由外部调用 mutateState。
     * 如果需要适配 BaseNode 的 run 接口，可以在这里抛出异常或实现特定逻辑。
     * 这里我们假设 run 方法不适用于 StateMutationNode 的典型用法，或者需要子类自行实现。
     * 但为了满足 BaseNode 的抽象方法，我们提供一个默认实现（可能需要根据实际情况调整）。
     */
    @Override
    public ReportState run(I inputData) {
        throw new UnsupportedOperationException("StateMutationNode should be called via mutateState(input, state)");
    }
}
