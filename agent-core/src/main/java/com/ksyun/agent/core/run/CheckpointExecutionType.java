package com.ksyun.agent.core.run;

/**
 * Checkpoint 执行类型枚举。
 * <p>
 * 区分不同执行模式的 Checkpoint。
 */
public enum CheckpointExecutionType {

    /** ReAct Agent 执行 */
    REACT_AGENT,

    /** Supervisor 执行（预留） */
    SUPERVISOR
}
