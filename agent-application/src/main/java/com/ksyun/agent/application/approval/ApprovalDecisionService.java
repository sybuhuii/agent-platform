package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.approval.ApprovalAction;
import com.ksyun.agent.core.approval.ApprovalDecision;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.security.UserSession;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 审批决定服务，纯 Java 实现。
 * <p>
 * 职责：记录审批决定，更新 Checkpoint 中的 PendingApproval。
 * 不执行恢复、不调用模型和工具、不删除 Checkpoint。
 * <p>
 * 状态转换规则：
 * - PENDING → APPROVE: 创建 ApprovalDecision(status=APPROVED)，更新 PendingApproval
 * - PENDING → REJECT: 创建 ApprovalDecision(status=REJECTED)，更新 PendingApproval
 * - APPROVED + 再次 APPROVE: 幂等返回已有决定，不增加 version
 * - APPROVED + 再次 REJECT: 抛 APPROVAL_ALREADY_DECIDED
 * - REJECTED + 再次 REJECT: 幂等返回已有决定，不增加 version
 * - REJECTED + 再次 APPROVE: 抛 APPROVAL_ALREADY_DECIDED
 * - RESUMING/COMPLETED/FAILED 的 Checkpoint 不得审批
 * <p>
 * 用户隔离：
 * - operator.userId 必须与 Checkpoint.userId 匹配
 * - 不得根据 username 判断归属
 * - 不得通过 approvalId 单独加载并执行
 * <p>
 * decidedBy 必须来自 operator.userId，不得来自请求 Body。
 * decidedAt 必须来自注入的 Clock。
 */
public class ApprovalDecisionService {

    private final CheckpointStore checkpointStore;
    private final Clock clock;

    public ApprovalDecisionService(CheckpointStore checkpointStore, Clock clock) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 记录审批决定。
     *
     * @param operator 当前操作用户，来自已验证 Session
     * @param command  审批决定命令
     * @return 审批决定结果
     */
    public ApprovalDecisionResult decide(UserSession operator, ApprovalDecisionCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. 加载 Checkpoint
        AgentCheckpoint checkpoint = checkpointStore.load(command.runId())
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found: runId=" + command.runId()));

        // 2. 校验归属用户 - 不匹配使用安全 NOT_FOUND，不泄漏信息
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 2.5 拒绝直接对 SUPERVISOR Checkpoint 执行审批决定
        // 父 Supervisor Checkpoint 中的 pendingApproval 是代表性子审批，
        // 真正可审批对象只有 executionType == REACT_AGENT 的子 Checkpoint
        if (checkpoint.executionType() == CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Supervisor checkpoint cannot be directly approved");
        }

        // 3. 校验 Checkpoint 状态
        validateCheckpointStatus(checkpoint);

        // 4. 校验 pendingApproval 存在
        PendingApproval currentApproval = checkpoint.pendingApproval();
        if (currentApproval == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_NOT_FOUND,
                    "No pending approval found in checkpoint");
        }

        // 5. 校验 approvalId 匹配
        if (!currentApproval.approvalId().equals(command.approvalId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "approvalId does not match");
        }

        // 6. 处理已有决定
        if (currentApproval.status() != ApprovalStatus.PENDING) {
            return handleExistingDecision(currentApproval, command.action(), checkpoint);
        }

        // 7. PENDING → 创建新决定
        ApprovalStatus newStatus = mapActionToStatus(command.action());
        Instant decidedAt = clock.instant();
        ApprovalDecision decision = new ApprovalDecision(
                currentApproval.approvalId(),
                newStatus,
                operator.userId(),
                command.comment(),
                decidedAt
        );

        // 8. 更新 PendingApproval
        PendingApproval updatedApproval = new PendingApproval(
                currentApproval.payload(),
                newStatus,
                decision,
                currentApproval.createdAt(),
                decidedAt
        );

        // 9. 更新 Checkpoint
        long expectedVersion = checkpoint.version();
        AgentCheckpoint updatedCheckpoint = new AgentCheckpoint(
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.threadId(),
                checkpoint.userId(),
                checkpoint.sessionId(),
                checkpoint.executionType(),
                checkpoint.purpose(),       // HITL_RECOVERY 保持不变
                checkpoint.agentName(),
                checkpoint.nodeName(),
                checkpoint.stateData(),
                updatedApproval,
                checkpoint.status(), // 保持 SUSPENDED
                expectedVersion + 1,
                checkpoint.createdAt(),
                decidedAt
        );

        // 10. 条件更新
        boolean success = checkpointStore.updateIfVersionMatches(updatedCheckpoint, expectedVersion);
        if (!success) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Checkpoint version conflict during approval decision");
        }

        return new ApprovalDecisionResult(
                checkpoint.runId(),
                checkpoint.threadId(),
                currentApproval.approvalId(),
                newStatus,
                currentApproval.payload().operationName(),
                operator.userId(),
                command.comment(),
                decidedAt,
                expectedVersion + 1
        );
    }

    /**
     * 处理已有决定（非 PENDING）。
     */
    private ApprovalDecisionResult handleExistingDecision(
            PendingApproval currentApproval, ApprovalAction newAction, AgentCheckpoint checkpoint) {
        ApprovalDecision existingDecision = currentApproval.decision();
        ApprovalAction existingAction = existingDecision.status() == ApprovalStatus.APPROVED
                ? ApprovalAction.APPROVE : ApprovalAction.REJECT;

        // 相同决定幂等返回
        if (newAction == existingAction) {
            return new ApprovalDecisionResult(
                    checkpoint.runId(),
                    checkpoint.threadId(),
                    currentApproval.approvalId(),
                    existingDecision.status(),
                    currentApproval.payload().operationName(),
                    existingDecision.decidedBy(),
                    existingDecision.comment(),
                    existingDecision.decidedAt(),
                    checkpoint.version()
            );
        }

        // 冲突决定
        throw new AgentFrameworkException(
                AgentErrorCode.APPROVAL_ALREADY_DECIDED,
                "Approval already decided as " + existingDecision.status()
                        + ", cannot change to " + mapActionToStatus(newAction));
    }

    private void validateCheckpointStatus(AgentCheckpoint checkpoint) {
        if (checkpoint.status() == CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint is in RESUMING state, cannot modify approval");
        }
        if (checkpoint.status() == CheckpointStatus.COMPLETED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is COMPLETED, cannot modify approval");
        }
        if (checkpoint.status() == CheckpointStatus.FAILED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is FAILED, cannot modify approval");
        }
    }

    private ApprovalStatus mapActionToStatus(ApprovalAction action) {
        return action == ApprovalAction.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
    }
}
