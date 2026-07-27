package com.ksyun.agent.runtime.react.checkpoint.validator;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;

import java.util.Map;

/**
 * Checkpoint 校验器，纯 Java 实现。
 * <p>
 * 校验内容：
 * - 必要字段
 * - runId、threadId、userId、nodeName
 * - version
 * - stateData
 * - SUSPENDED 与 pendingApproval
 * - ApprovalStatus 与 ApprovalDecision 一致性
 * <p>
 * 失败使用 AgentFrameworkException 和明确错误码。
 * 不访问 Spring 容器。不调用模型和工具。不修改 Checkpoint。
 */
public class CheckpointValidator {

    /**
     * 校验 Checkpoint。
     *
     * @param checkpoint 待校验的 Checkpoint
     * @throws AgentFrameworkException 校验失败
     */
    public void validate(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "AgentCheckpoint must not be null");
        }

        // runId 非空（已在 AgentCheckpoint 构造器中校验，防御性检查）
        validateNotBlank(checkpoint.runId(), "runId");
        validateNotBlank(checkpoint.threadId(), "threadId");
        validateNotBlank(checkpoint.userId(), "userId");
        validateNotBlank(checkpoint.nodeName(), "nodeName");

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

        // SUSPENDED 与 pendingApproval 一致性
        if (checkpoint.status() == CheckpointStatus.SUSPENDED && checkpoint.pendingApproval() == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "SUSPENDED Checkpoint must have pendingApproval");
        }
        if (checkpoint.status() != CheckpointStatus.SUSPENDED && checkpoint.pendingApproval() != null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Non-SUSPENDED Checkpoint must not have pendingApproval");
        }

        // ApprovalStatus 与 ApprovalDecision 一致性
        if (checkpoint.pendingApproval() != null) {
            validateApprovalConsistency(checkpoint.pendingApproval());
        }
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
