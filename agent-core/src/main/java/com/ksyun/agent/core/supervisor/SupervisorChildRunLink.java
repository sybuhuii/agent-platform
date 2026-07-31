package com.ksyun.agent.core.supervisor;

import java.io.Serializable;
import java.util.Objects;

/**
 * Supervisor 子 Agent 运行关联值对象，不可变。
 * <p>
 * 在 Supervisor Dispatch 节点中创建，用于建立父子运行之间的稳定关联。
 * 该对象会随子 Agent 的 AgentTask.context 进入 React State 和 Checkpoint，
 * 后续审批恢复时从 Checkpoint 中获取，用于定位父 Supervisor 运行和子任务位置。
 * <p>
 * 约束：
 * - 所有字符串字段必须非空、非空白
 * - dispatchIndex 必须大于等于 0
 * - 不包含 sessionId、roles、permissions、密码、Token、API Key
 * - 不依赖 Spring、Jackson、LangGraph4j 或数据库类型
 * - 不保存 Supervisor 或子 Agent 完整 State
 * - 不把 RunContext 整体放进该关联对象
 * - 不使用 Map<String, Object> 代替该值对象
 *
 * @param parentRunId      父 Supervisor runId
 * @param parentThreadId   父 Supervisor threadId
 * @param parentTaskId     父根任务 ID
 * @param dispatchBatchId  本轮分派批次 ID
 * @param childRunId       子 Agent runId
 * @param childThreadId    子 Agent threadId
 * @param childTaskId      子任务 ID
 * @param dispatchIndex    子任务在本轮分派中的稳定序号，从 0 开始
 */
public record SupervisorChildRunLink(
        String parentRunId,
        String parentThreadId,
        String parentTaskId,
        String dispatchBatchId,
        String childRunId,
        String childThreadId,
        String childTaskId,
        int dispatchIndex
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内部上下文键，用于在 AgentTask.context 中存储关联。
     * 框架内部保留键，调用方不得设置或覆盖。
     */
    public static final String TASK_CONTEXT_KEY = "supervisorChildRunLink";

    public SupervisorChildRunLink {
        Objects.requireNonNull(parentRunId, "parentRunId must not be null");
        if (parentRunId.isBlank()) {
            throw new IllegalArgumentException("parentRunId must not be blank");
        }
        Objects.requireNonNull(parentThreadId, "parentThreadId must not be null");
        if (parentThreadId.isBlank()) {
            throw new IllegalArgumentException("parentThreadId must not be blank");
        }
        Objects.requireNonNull(parentTaskId, "parentTaskId must not be null");
        if (parentTaskId.isBlank()) {
            throw new IllegalArgumentException("parentTaskId must not be blank");
        }
        Objects.requireNonNull(dispatchBatchId, "dispatchBatchId must not be null");
        if (dispatchBatchId.isBlank()) {
            throw new IllegalArgumentException("dispatchBatchId must not be blank");
        }
        Objects.requireNonNull(childRunId, "childRunId must not be null");
        if (childRunId.isBlank()) {
            throw new IllegalArgumentException("childRunId must not be blank");
        }
        Objects.requireNonNull(childThreadId, "childThreadId must not be null");
        if (childThreadId.isBlank()) {
            throw new IllegalArgumentException("childThreadId must not be blank");
        }
        Objects.requireNonNull(childTaskId, "childTaskId must not be null");
        if (childTaskId.isBlank()) {
            throw new IllegalArgumentException("childTaskId must not be blank");
        }
        if (dispatchIndex < 0) {
            throw new IllegalArgumentException("dispatchIndex must be >= 0");
        }
    }
}
