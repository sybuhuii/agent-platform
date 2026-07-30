package com.ksyun.agent.runtime.react.checkpoint.validator;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadCheckpointStateMapper;

import java.util.Objects;
import java.util.Map;

/**
 * Checkpoint 校验器，纯 Java 实现。
 * <p>
 * 根据 purpose 执行不同校验：
 * - HITL_RECOVERY：保持现有第六阶段规则，不得弱化
 * - THREAD_MEMORY：新增校验规则
 * <p>
 * 失败使用 AgentFrameworkException 和明确错误码。
 * 不访问 Spring 容器。不调用模型和工具。不修改 Checkpoint。
 * 错误信息不得包含完整 stateData 和消息正文。
 */
public class CheckpointValidator {

    private final ThreadCheckpointStateMapper threadStateMapper;

    public CheckpointValidator(
            ThreadCheckpointStateMapper threadStateMapper
    ) {
        this.threadStateMapper = Objects.requireNonNull(
                threadStateMapper,
                "threadStateMapper must not be null");
    }

    /**
     * 校验 Checkpoint。
     * <p>
     * 根据 purpose 分支校验。
     *
     * @param checkpoint 待校验的 Checkpoint
     * @throws AgentFrameworkException 校验失败
     */
    public void validate(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "AgentCheckpoint must not be null");
        }

        // 公共校验
        validateNotBlank(checkpoint.runId(), "runId");
        validateNotBlank(checkpoint.threadId(), "threadId");
        validateNotBlank(checkpoint.userId(), "userId");

        // version
        if (checkpoint.version() < 0) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Checkpoint version must be >= 0, got " + checkpoint.version());
        }

        // stateData 非空
        if (checkpoint.stateData() == null || checkpoint.stateData().isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Checkpoint stateData must not be empty");
        }

        // purpose 不能为空（AgentCheckpoint构造器已校验，防御性检查）
        if (checkpoint.purpose() == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Checkpoint purpose must not be null");
        }

        // 按 purpose 分支校验
        if (checkpoint.purpose() == CheckpointPurpose.HITL_RECOVERY) {
            validateHITLRecovery(checkpoint);
        } else if (checkpoint.purpose() == CheckpointPurpose.THREAD_MEMORY) {
            validateThreadMemory(checkpoint);
        }
    }

    /**
     * HITL_RECOVERY 校验，保持现有第六阶段规则，不得弱化。
     */
    private void validateHITLRecovery(AgentCheckpoint checkpoint) {
        validateNotBlank(checkpoint.nodeName(), "nodeName");

        // SUSPENDED 与 pendingApproval 一致性
        if (checkpoint.status() == CheckpointStatus.SUSPENDED && checkpoint.pendingApproval() == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "SUSPENDED Checkpoint must have pendingApproval");
        }
        if (checkpoint.status() == CheckpointStatus.RESUMING && checkpoint.pendingApproval() == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "RESUMING Checkpoint must have pendingApproval");
        }
        if ((checkpoint.status() == CheckpointStatus.COMPLETED || checkpoint.status() == CheckpointStatus.FAILED)
                && checkpoint.pendingApproval() != null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "COMPLETED/FAILED Checkpoint must not have pendingApproval");
        }

        // ApprovalStatus 与 ApprovalDecision 一致性
        if (checkpoint.pendingApproval() != null) {
            validateApprovalConsistency(checkpoint.pendingApproval());
        }
    }

    /**
     * THREAD_MEMORY 校验，新增规则。
     * <p>
     * 不得使用 HITL 的 SUSPENDED 校验规则。
     * 错误信息不得包含完整 stateData 和消息正文。
     */
    private void validateThreadMemory(AgentCheckpoint checkpoint) {
        // purpose 必须为 THREAD_MEMORY（已在分支判断中确认）
        // status 必须为 COMPLETED
        if (checkpoint.status() != CheckpointStatus.COMPLETED) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_MEMORY Checkpoint status must be COMPLETED, got " + checkpoint.status());
        }

        // userId 不能为空（已在公共校验中确认）
        // threadId 不能为空（已在公共校验中确认）
        // runId 不能为空（已在公共校验中确认）
        // executionType 不能为空
        if (checkpoint.executionType() == null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_MEMORY Checkpoint executionType must not be null");
        }

        // pendingApproval 必须为空
        if (checkpoint.pendingApproval() != null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_MEMORY Checkpoint must not contain PendingApproval");
        }

        // stateData 不能为空（已在公共校验中确认）
        // stateData 必须能够恢复 ThreadConversationState（由 ThreadCheckpointStateMapper 校验）
        // 不得包含待审批对象（pendingApproval == null 已确认）
        // 不得包含完整 UserSession、Session ID（由保存流程保证）
        if (checkpoint.stateData().size() != 1) {
            throw new AgentFrameworkException(
                    AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_MEMORY stateData contains unsupported transient fields");
        }

        threadStateMapper.fromCheckpoint(checkpoint);
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Checkpoint " + fieldName + " must not be blank");
        }
    }

    private void validateApprovalConsistency(PendingApproval approval) {
        // PENDING 时 decision 必须为空
        if (approval.status() == ApprovalStatus.PENDING && approval.decision() != null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "PENDING approval must not have decision");
        }

        // APPROVED 或 REJECTED 时 decision 必须存在
        if ((approval.status() == ApprovalStatus.APPROVED || approval.status() == ApprovalStatus.REJECTED)
                && approval.decision() == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "APPROVED/REJECTED approval must have decision");
        }
    }
}
