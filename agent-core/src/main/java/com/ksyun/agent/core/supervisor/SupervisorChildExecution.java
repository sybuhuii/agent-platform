package com.ksyun.agent.core.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.run.RunStatus;

import java.io.Serializable;
import java.util.Objects;

/**
 * Supervisor 子任务执行记录，不可变。
 * <p>
 * 追踪每个子任务在当前分派批次中的执行状态、关联和结果。
 * <p>
 * 状态与字段约束：
 * <ul>
 *   <li>NOT_STARTED: task 非空, dispatchIndex >= 0, runLink 可空, result 必须为空, approvalId 必须为空</li>
 *   <li>RUNNING: task 非空, dispatchIndex >= 0, runLink 非空, result 必须为空, approvalId 必须为空</li>
 *   <li>COMPLETED/FAILED: task 非空, dispatchIndex >= 0, runLink 非空, result 非空, approvalId 通常为空</li>
 *   <li>SUSPENDED: task 非空, dispatchIndex >= 0, runLink 非空, result 非空且 status==SUSPENDED, approvalId 非空</li>
 * </ul>
 * <p>
 * 不保存完整子 Agent State。
 * 不保存 RunContext。
 * 不保存原始工具参数。
 * 不保存 Session ID、角色和权限。
 * 不使用可变集合。
 *
 * @param task          子任务定义，非空
 * @param dispatchIndex 子任务在当前分派批次中的稳定序号，>= 0
 * @param runLink       父子运行关联
 * @param status        执行状态
 * @param result        子 Agent 执行结果
 * @param approvalId    审批 ID（SUSPENDED 时非空）
 */
public record SupervisorChildExecution(
        AgentTask task,
        int dispatchIndex,
        SupervisorChildRunLink runLink,
        SupervisorChildExecutionStatus status,
        AgentResult result,
        String approvalId
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SupervisorChildExecution {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (dispatchIndex < 0) {
            throw new IllegalArgumentException("dispatchIndex must be >= 0");
        }

        switch (status) {
            case NOT_STARTED -> {
                // result 必须为空
                if (result != null) {
                    throw new IllegalArgumentException("result must be null when status is NOT_STARTED");
                }
                // approvalId 必须为空
                if (approvalId != null) {
                    throw new IllegalArgumentException("approvalId must be null when status is NOT_STARTED");
                }
            }
            case RUNNING -> {
                // runLink 非空
                Objects.requireNonNull(runLink, "runLink must not be null when status is RUNNING");
                // result 必须为空
                if (result != null) {
                    throw new IllegalArgumentException("result must be null when status is RUNNING");
                }
                // approvalId 必须为空
                if (approvalId != null) {
                    throw new IllegalArgumentException("approvalId must be null when status is RUNNING");
                }
            }
            case COMPLETED, FAILED -> {
                // runLink 非空
                Objects.requireNonNull(runLink, "runLink must not be null when status is " + status);
                // result 非空
                Objects.requireNonNull(result, "result must not be null when status is " + status);
            }
            case SUSPENDED -> {
                // runLink 非空
                Objects.requireNonNull(runLink, "runLink must not be null when status is SUSPENDED");
                // result 非空且 status 必须为 SUSPENDED
                Objects.requireNonNull(result, "result must not be null when status is SUSPENDED");
                if (result.status() != RunStatus.SUSPENDED) {
                    throw new IllegalArgumentException(
                            "result.status must be SUSPENDED when execution status is SUSPENDED, got " + result.status());
                }
                // approvalId 非空
                Objects.requireNonNull(approvalId, "approvalId must not be null when status is SUSPENDED");
                if (approvalId.isBlank()) {
                    throw new IllegalArgumentException("approvalId must not be blank when status is SUSPENDED");
                }
            }
            case CANCELLED -> {
                // 未来并行预留，本批不使用
            }
        }
    }

    /**
     * 创建 NOT_STARTED 记录。
     */
    public static SupervisorChildExecution notStarted(AgentTask task, int dispatchIndex) {
        return new SupervisorChildExecution(task, dispatchIndex, null,
                SupervisorChildExecutionStatus.NOT_STARTED, null, null);
    }

    /**
     * 创建 RUNNING 记录。
     */
    public static SupervisorChildExecution running(AgentTask task, int dispatchIndex,
                                                    SupervisorChildRunLink runLink) {
        return new SupervisorChildExecution(task, dispatchIndex, runLink,
                SupervisorChildExecutionStatus.RUNNING, null, null);
    }

    /**
     * 创建 COMPLETED 记录。
     */
    public static SupervisorChildExecution completed(AgentTask task, int dispatchIndex,
                                                      SupervisorChildRunLink runLink,
                                                      AgentResult result) {
        return new SupervisorChildExecution(task, dispatchIndex, runLink,
                SupervisorChildExecutionStatus.COMPLETED, result, null);
    }

    /**
     * 创建 FAILED 记录。
     */
    public static SupervisorChildExecution failed(AgentTask task, int dispatchIndex,
                                                   SupervisorChildRunLink runLink,
                                                   AgentResult result) {
        return new SupervisorChildExecution(task, dispatchIndex, runLink,
                SupervisorChildExecutionStatus.FAILED, result, null);
    }

    /**
     * 创建 SUSPENDED 记录。
     */
    public static SupervisorChildExecution suspended(AgentTask task, int dispatchIndex,
                                                      SupervisorChildRunLink runLink,
                                                      AgentResult result,
                                                      String approvalId) {
        return new SupervisorChildExecution(task, dispatchIndex, runLink,
                SupervisorChildExecutionStatus.SUSPENDED, result, approvalId);
    }
}
