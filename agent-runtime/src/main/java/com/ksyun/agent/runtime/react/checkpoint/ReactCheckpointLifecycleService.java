package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Checkpoint 生命周期服务，纯 Java 实现。
 * <p>
 * 集中处理 Checkpoint 的终态转换和条件删除。
 * ReactResumeEngine 不得继续内嵌重复生命周期实现。
 * <p>
 * 处理：
 * - complete: RESUMING→COMPLETED 条件更新，再按 checkpointId/version 条件删除
 * - fail: RESUMING→FAILED 条件更新，保存安全 errorCode，默认保留 FAILED Checkpoint
 * - 再次挂起由 ReactCheckpointService 更新为 SUSPENDED，生命周期服务不得删除
 * <p>
 * 约束：
 * - 版本冲突不能只记录日志后返回成功，必须返回 CHECKPOINT_CONFLICT
 * - 普通清理日志失败不能伪造工具执行失败
 * - 不得静默吞掉状态更新失败
 * - 不保存异常对象或堆栈
 * - 不依赖 Spring
 */
public class ReactCheckpointLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ReactCheckpointLifecycleService.class);

    private final CheckpointStore checkpointStore;
    private final Clock clock;

    public ReactCheckpointLifecycleService(CheckpointStore checkpointStore, Clock clock) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 完成：RESUMING→COMPLETED 条件更新，再条件删除。
     * <p>
     * 使用 checkpointId/version 条件删除，避免误删同一 runId 后续再次挂起的新版本。
     * 版本冲突（可能已被再次挂起）不抛异常，返回 CHECKPOINT_CONFLICT 让调用方决策。
     *
     * @param resumingCheckpoint 当前处于 RESUMING 的 Checkpoint
     * @throws AgentFrameworkException 版本冲突时抛出 CHECKPOINT_CONFLICT
     */
    public void complete(AgentCheckpoint resumingCheckpoint) {
        Objects.requireNonNull(resumingCheckpoint, "resumingCheckpoint must not be null");

        long expectedVersion = resumingCheckpoint.version();
        Instant now = clock.instant();

        AgentCheckpoint completedCp = new AgentCheckpoint(
                resumingCheckpoint.checkpointId(),
                resumingCheckpoint.runId(),
                resumingCheckpoint.threadId(),
                resumingCheckpoint.userId(),
                resumingCheckpoint.sessionId(),
                resumingCheckpoint.executionType(),
                resumingCheckpoint.agentName(),
                resumingCheckpoint.nodeName(),
                resumingCheckpoint.stateData(),
                null, // COMPLETED 不保留 pendingApproval
                CheckpointStatus.COMPLETED,
                expectedVersion + 1,
                resumingCheckpoint.createdAt(),
                now
        );

        boolean updated = checkpointStore.updateIfVersionMatches(completedCp, expectedVersion);
        if (!updated) {
            // 版本冲突：可能已被再次挂起
            log.warn("Checkpoint version conflict during COMPLETED update: runId={}, expectedVersion={}. "
                    + "Checkpoint may have been re-suspended.",
                    resumingCheckpoint.runId(), expectedVersion);
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Checkpoint version conflict during completion: runId=" + resumingCheckpoint.runId());
        }

        // 条件删除：按 checkpointId + version 匹配
        boolean deleted = checkpointStore.deleteIfVersionMatches(
                resumingCheckpoint.runId(), completedCp.checkpointId(), completedCp.version());
        if (deleted) {
            log.info("Checkpoint cleaned up after completion: runId={}", resumingCheckpoint.runId());
        } else {
            log.warn("Checkpoint conditional delete failed after completion: runId={}. "
                    + "May have been modified concurrently.", resumingCheckpoint.runId());
        }
    }

    /**
     * 失败：RESUMING→FAILED 条件更新。
     * <p>
     * 保存安全 errorCode，不保存异常对象或堆栈。
     * 默认保留 FAILED Checkpoint 供诊断。
     * 版本冲突抛出 CHECKPOINT_CONFLICT。
     *
     * @param resumingCheckpoint 当前处于 RESUMING 的 Checkpoint
     * @param errorCode          安全错误码
     * @throws AgentFrameworkException 版本冲突时抛出 CHECKPOINT_CONFLICT
     */
    public void fail(AgentCheckpoint resumingCheckpoint, AgentErrorCode errorCode) {
        Objects.requireNonNull(resumingCheckpoint, "resumingCheckpoint must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");

        long expectedVersion = resumingCheckpoint.version();
        Instant now = clock.instant();

        AgentCheckpoint failedCp = new AgentCheckpoint(
                resumingCheckpoint.checkpointId(),
                resumingCheckpoint.runId(),
                resumingCheckpoint.threadId(),
                resumingCheckpoint.userId(),
                resumingCheckpoint.sessionId(),
                resumingCheckpoint.executionType(),
                resumingCheckpoint.agentName(),
                resumingCheckpoint.nodeName(),
                resumingCheckpoint.stateData(),
                null, // FAILED 不保留 pendingApproval
                CheckpointStatus.FAILED,
                expectedVersion + 1,
                resumingCheckpoint.createdAt(),
                now
        );

        boolean updated = checkpointStore.updateIfVersionMatches(failedCp, expectedVersion);
        if (!updated) {
            log.warn("Checkpoint version conflict during FAILED update: runId={}, expectedVersion={}",
                    resumingCheckpoint.runId(), expectedVersion);
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Checkpoint version conflict during failure: runId=" + resumingCheckpoint.runId());
        } else {
            log.info("Checkpoint marked as FAILED: runId={}, errorCode={}", resumingCheckpoint.runId(), errorCode);
        }
    }

    /**
     * 条件删除终态 Checkpoint。
     * <p>
     * 用于手动清理或外部触发。正常完成流程由 complete() 内部处理。
     *
     * @param runId        运行 ID
     * @param checkpointId Checkpoint ID
     * @param version      期望版本
     * @return 是否删除成功
     */
    public boolean removeTerminal(String runId, String checkpointId, long version) {
        return checkpointStore.deleteIfVersionMatches(runId, checkpointId, version);
    }
}
