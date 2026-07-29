package com.ksyun.agent.core.run;

/**
 * Checkpoint 用途类型。
 * <p>
 * 不得用 metadata 字符串替代。
 * 不得用 CheckpointStatus 代替。
 * 不得用 CheckpointExecutionType 代替。
 * 所有现有 HITL Checkpoint 必须明确属于 HITL_RECOVERY。
 * 不得把旧 HITL 状态默认为 THREAD_MEMORY。
 * 不得增加第三个本批未要求的 purpose。
 */
public enum CheckpointPurpose {

    /**
     * 用于 HITL 恢复：
     * - 某一次 runId 中断
     * - 危险工具审批
     * - 从中断节点恢复
     * - 保存 PendingApproval
     * - 保存执行游标和临时运行状态
     */
    HITL_RECOVERY,

    /**
     * 用于线程短期记忆：
     * - 保存一次正常执行结束后的稳定会话状态
     * - 支持同一 threadId 后续调用续接
     * - 保存完整会话消息
     * - 保存上下文窗口 Snapshot
     * - 按 userId+threadId 加载
     * - 不得保存 PendingApproval
     * - 不得表示 Java 代码行级恢复
     */
    THREAD_MEMORY
}
