package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Checkpoint 恢复抢占协调器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 加载 Checkpoint 用于 Validator 校验（不修改状态）
 * 2. 原子抢占 SUSPENDED → RESUMING
 * 3. 并发恢复只有一个成功
 * <p>
 * 不实现审批决定。不调用模型和工具。
 */
public class CheckpointResumeCoordinator {

    private final CheckpointStore checkpointStore;
    private final ReactResumeValidator resumeValidator;
    private final Clock clock;

    public CheckpointResumeCoordinator(CheckpointStore checkpointStore,
                                         ReactResumeValidator resumeValidator,
                                         Clock clock) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.resumeValidator = Objects.requireNonNull(resumeValidator);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 加载 Checkpoint 用于 Validator 校验，不修改状态。
     *
     * @param runId 运行 ID
     * @return 加载的 Checkpoint
     * @throws AgentFrameworkException CHECKPOINT_NOT_FOUND
     */
    public AgentCheckpoint loadForValidation(String runId) {
        return checkpointStore.load(runId).orElseThrow(() ->
                new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found: runId=" + runId));
    }

    /**
     * 原子抢占 SUSPENDED → RESUMING。
     * <p>
     * 使用 version 条件更新，并发恢复只有一个成功。
     * 必须在调用前通过 Validator 校验。
     *
     * @param runId    运行 ID
     * @param operator 当前操作用户
     * @return 抢占后的 RESUMING Checkpoint
     * @throws AgentFrameworkException CHECKPOINT_NOT_FOUND / RUN_ALREADY_RESUMING / CHECKPOINT_CONFLICT
     */
    public AgentCheckpoint acquireForResume(String runId, UserSession operator) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        AgentCheckpoint checkpoint = checkpointStore.load(runId).orElseThrow(() ->
                new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found"));

        // 不匹配用户使用安全 NOT_FOUND
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 必须是 SUSPENDED 状态
        if (checkpoint.status() == CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint is already in RESUMING state");
        }
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is not in SUSPENDED state: " + checkpoint.status());
        }

        // 审批必须已决定
        PendingApproval approval = checkpoint.pendingApproval();
        if (approval == null) {
            throw new AgentFrameworkException(AgentErrorCode.APPROVAL_NOT_FOUND,
                    "No pending approval found in checkpoint");
        }
        if (approval.status() != ApprovalStatus.APPROVED
                && approval.status() != ApprovalStatus.REJECTED) {
            throw new AgentFrameworkException(AgentErrorCode.APPROVAL_REQUIRED,
                    "Approval has not been decided yet");
        }

        // 原子更新 SUSPENDED → RESUMING
        long expectedVersion = checkpoint.version();
        Instant now = clock.instant();

        AgentCheckpoint resumingCheckpoint = new AgentCheckpoint(
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.threadId(),
                checkpoint.userId(),
                checkpoint.sessionId(),
                checkpoint.executionType(),
                checkpoint.agentName(),
                checkpoint.nodeName(),
                checkpoint.stateData(),
                checkpoint.pendingApproval(), // RESUMING 保留已决策的 pendingApproval
                CheckpointStatus.RESUMING,
                expectedVersion + 1,
                checkpoint.createdAt(),
                now
        );

        boolean success = checkpointStore.updateIfVersionMatches(resumingCheckpoint, expectedVersion);
        if (!success) {
            throw new AgentFrameworkException(AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint version conflict or already resuming: runId=" + runId);
        }

        return resumingCheckpoint;
    }
}
