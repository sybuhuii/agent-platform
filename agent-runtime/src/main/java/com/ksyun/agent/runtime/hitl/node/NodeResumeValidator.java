package com.ksyun.agent.runtime.hitl.node;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.NodeResumeData;
import com.ksyun.agent.core.approval.OperationType;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.runtime.react.ReactStateKeys;

import java.util.Map;
import java.util.Objects;

/** NODE Checkpoint 恢复前的通用安全校验。 */
public final class NodeResumeValidator {

    public void validate(
            AgentCheckpoint checkpoint,
            UserSession operator,
            String requestedRunId) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(requestedRunId, "requestedRunId must not be null");

        if (checkpoint == null
                || !checkpoint.runId().equals(requestedRunId)
                || !checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }
        if (checkpoint.executionType() != CheckpointExecutionType.REACT_AGENT
                || checkpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            throw notResumable("Checkpoint type is not resumable as a NODE interrupt");
        }
        if (checkpoint.status() == CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint is already resuming");
        }
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw notResumable("Checkpoint is not suspended");
        }

        PendingApproval approval = checkpoint.pendingApproval();
        if (approval == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_NOT_FOUND,
                    "Node approval is missing");
        }
        if (approval.payload().operationType() != OperationType.NODE) {
            throw notResumable("Approval operationType is not NODE");
        }
        if (approval.status() != ApprovalStatus.APPROVED
                && approval.status() != ApprovalStatus.REJECTED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_REQUIRED,
                    "Node approval has not been decided");
        }
        if (approval.decision() == null
                || approval.decision().decidedBy() == null
                || approval.decision().decidedBy().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Node approval decision is invalid");
        }
        if (!checkpoint.runId().equals(approval.payload().runId())
                || !checkpoint.threadId().equals(approval.payload().threadId())
                || !checkpoint.userId().equals(approval.payload().userId())
                || !checkpoint.agentName().equals(approval.payload().agentName())
                || !checkpoint.nodeName().equals(approval.payload().nodeName())) {
            throw notResumable("Node approval identity does not match checkpoint");
        }

        Map<String, Object> stateData = checkpoint.stateData();
        Object handlerKey = stateData.get(ReactStateKeys.NODE_RESUME_HANDLER_KEY);
        if (!(handlerKey instanceof String key) || key.isBlank()) {
            throw notResumable("Node resume handler key is missing");
        }
        Object resumeData = stateData.get(ReactStateKeys.NODE_RESUME_DATA);
        if (!(resumeData instanceof NodeResumeData)) {
            throw notResumable("Node resume data is missing or invalid");
        }
        // RunContext 不再持久化到 payload，恢复时由当前 UserSession 重建。
        // runId/threadId/userId 一致性已由用户归属校验与 payload 身份校验覆盖。
    }

    private AgentFrameworkException notResumable(String message) {
        return new AgentFrameworkException(
                AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                message);
    }
}
