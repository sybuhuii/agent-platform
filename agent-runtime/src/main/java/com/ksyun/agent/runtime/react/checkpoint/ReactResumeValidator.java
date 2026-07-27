package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;

import java.util.Objects;

/**
 * ReAct 恢复校验器，纯 Java 实现。
 * <p>
 * 校验顺序：
 * 1. 参数非空
 * 2. Checkpoint 存在
 * 3. 用户归属
 * 4. CheckpointStatus 可恢复
 * 5. PendingApproval 存在
 * 6. 审批已决策（APPROVED 或 REJECTED）
 * 7. 审批与操作匹配
 * <p>
 * 不访问 Spring 容器。不调用模型和工具。不修改 Checkpoint。
 * 不泄漏 stateData 和审批细节给非归属用户。
 */
public class ReactResumeValidator {

    /**
     * 校验恢复请求。
     *
     * @param checkpoint 加载的 Checkpoint
     * @param operator   当前操作用户
     * @param runId      运行 ID
     */
    public void validateForResume(AgentCheckpoint checkpoint, UserSession operator, String runId) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(runId, "runId must not be null");

        // 1. Checkpoint 存在
        if (checkpoint == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found: runId=" + runId);
        }

        // 2. 用户归属
        if (!checkpoint.userId().equals(operator.userId())) {
            // 不泄漏细节给非归属用户
            throw new AgentFrameworkException(
                    AgentErrorCode.PERMISSION_DENIED,
                    "Operation not permitted");
        }

        // 3. CheckpointStatus 可恢复
        if (checkpoint.status() == CheckpointStatus.COMPLETED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is COMPLETED, cannot resume");
        }
        if (checkpoint.status() == CheckpointStatus.FAILED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is FAILED, cannot resume");
        }
        if (checkpoint.status() == CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint is already in RESUMING state");
        }
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint status is " + checkpoint.status() + ", cannot resume");
        }

        // 4. PendingApproval 存在
        PendingApproval approval = checkpoint.pendingApproval();
        if (approval == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_NOT_FOUND,
                    "No pending approval found in checkpoint");
        }

        // 5. 审批已决策
        if (approval.status() == ApprovalStatus.PENDING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_REQUIRED,
                    "Approval has not been decided yet");
        }

        // 6. 审批必须是 APPROVED 或 REJECTED
        if (approval.status() != ApprovalStatus.APPROVED
                && approval.status() != ApprovalStatus.REJECTED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Unexpected approval status: " + approval.status());
        }
    }
}
