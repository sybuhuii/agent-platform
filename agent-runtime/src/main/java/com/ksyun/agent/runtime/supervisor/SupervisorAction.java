package com.ksyun.agent.runtime.supervisor;

/**
 * Supervisor 动作类型。
 * <p>
 * 本阶段不加入 THINK 工具，不实现自然语言反思工具。
 */
public enum SupervisorAction {

    /** 分派子 Agent 执行 */
    DISPATCH,

    /** Supervisor 决定完成，不再分派 */
    FINISH
}
