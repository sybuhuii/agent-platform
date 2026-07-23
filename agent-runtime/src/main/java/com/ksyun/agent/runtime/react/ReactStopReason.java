package com.ksyun.agent.runtime.react;

/**
 * ReAct 执行停止原因。
 * <p>
 * 描述 ReAct 循环结束的原因，不替代现有 RunStatus。
 */
public enum ReactStopReason {

    /** 模型不再请求工具，产生最终回答 */
    MODEL_COMPLETED,

    /** 达到 AgentDefinition.maxIterations 上限 */
    MAX_ITERATIONS_REACHED,

    /** 模型调用出错 */
    MODEL_ERROR,

    /** 工具执行出错 */
    TOOL_ERROR,

    /** 状态非法或节点执行失败 */
    INVALID_STATE
}
