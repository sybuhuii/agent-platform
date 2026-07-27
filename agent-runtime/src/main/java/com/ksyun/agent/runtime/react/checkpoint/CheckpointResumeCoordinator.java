package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.security.UserSession;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Checkpoint 恢复抢占协调器，纯 Java 实现。
 * <p>
 * 职责：原子 SUSPENDED → RESUMING 状态切换。
 * 使用 updateIfVersionMatches 条件更新，不使用 synchronized(runId.intern())。
 * 不直接执行恢复。不调用模型或工具。
 * 不删除 Checkpoint。
 * <p>
 * 并发恢复只有一个成功：
 * - 第一请求成功切换为 RESUMING
 * - 第二请求得到 RUN_ALREADY_RESUMING 或 CHECKPOINT_CONFLICT
 * - 危险工具最多执行一次
 * <p>
 * 用户隔离：
 * - operator.userId 必须与 Checkpoint.userId 匹配
 * - 不根据 username 判断归属
 * - 不通过 approvalId 单独加载
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
     * 原子抢占：SUSPENDED → RESUMING。
     * <p>
     * 成功返回更新后的 Checkpoint。
     * 失败抛出 AgentFrameworkException。
     *
     * @param runId    运行 ID
     * @param operator 当前操作用户
     * @return 已切换为 RESUMING 的 Checkpoint
     */
    public AgentCheckpoint acquireForResume(String runId, UserSession operator) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        // 1. 加载 Checkpoint
        AgentCheckpoint checkpoint = checkpointStore.load(runId)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found: runId=" + runId));

        // 2. 校验恢复条件
        resumeValidator.validateForResume(checkpoint, operator, runId);

        // 3. 原子切换 SUSPENDED → RESUMING
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
                checkpoint.pendingApproval(), // 保留已决策的 approval
                CheckpointStatus.RESUMING,
                expectedVersion + 1,
                checkpoint.createdAt(),
                now
        );

        // 4. 条件更新
        boolean success = checkpointStore.updateIfVersionMatches(resumingCheckpoint, expectedVersion);
        if (!success) {
            throw new AgentFrameworkException(
                    AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint version conflict or already resuming: runId=" + runId);
        }

        return resumingCheckpoint;
    }
}
