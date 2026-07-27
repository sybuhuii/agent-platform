package com.ksyun.agent.core.run;

/**
 * Checkpoint 状态枚举。
 * <p>
 * 不使用 RunStatus.INTERRUPTED 代替 CheckpointStatus。
 * 约束：
 * - SUSPENDED 必须有 pendingApproval
 * - COMPLETED 不得继续恢复
 */
public enum CheckpointStatus {

    /** 挂起，等待人工审批 */
    SUSPENDED,

    /** 正在恢复（第3批使用） */
    RESUMING,

    /** 已完成 */
    COMPLETED,

    /** 失败 */
    FAILED
}
