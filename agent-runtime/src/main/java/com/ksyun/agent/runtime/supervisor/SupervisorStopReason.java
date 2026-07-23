package com.ksyun.agent.runtime.supervisor;

/**
 * Supervisor 停止原因。
 * <p>
 * 描述 Supervisor 图终止原因，不替代 RunStatus。
 */
public enum SupervisorStopReason {

    /** Supervisor 正常完成，已生成最终回答 */
    COMPLETED,

    /** 达到最大迭代次数 */
    MAX_ITERATIONS_REACHED,

    /** 模型调用出错 */
    MODEL_ERROR,

    /** 子 Agent 执行出错 */
    AGENT_ERROR,

    /** 状态非法 */
    INVALID_STATE
}
