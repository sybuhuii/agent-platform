package com.ksyun.agent.runtime.checkpoint.thread;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 线程会话 Checkpoint 服务，纯 Java 实现。
 * <p>
 * 依赖：CheckpointStore、CheckpointValidator、ThreadCheckpointStateMapper、
 * CheckpointIdGenerator、Clock。
 * <p>
 * 不得访问 MemoryStore。不得把 ThreadConversationState 写入 MemoryStore。
 * 保持无状态和线程安全。
 */
public class ThreadConversationCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(ThreadConversationCheckpointService.class);

    private final CheckpointStore checkpointStore;
    private final CheckpointValidator checkpointValidator;
    private final ThreadCheckpointStateMapper stateMapper;
    private final CheckpointIdGenerator checkpointIdGenerator;
    private final Clock clock;

    public ThreadConversationCheckpointService(CheckpointStore checkpointStore,
                                                CheckpointValidator checkpointValidator,
                                                ThreadCheckpointStateMapper stateMapper,
                                                CheckpointIdGenerator checkpointIdGenerator,
                                                Clock clock) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "CheckpointStore must not be null");
        this.checkpointValidator = Objects.requireNonNull(checkpointValidator, "CheckpointValidator must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "ThreadCheckpointStateMapper must not be null");
        this.checkpointIdGenerator = Objects.requireNonNull(checkpointIdGenerator, "CheckpointIdGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * 加载线程会话状态。
     * <p>
     * 流程：
     * 1. 校验 userId、threadId、executionType、participantName
     * 2. loadLatestByThreadId(userId, threadId, THREAD_MEMORY)
     * 3. 校验 Checkpoint 归属和 purpose
     * 4. 通过 ThreadCheckpointStateMapper 恢复
     * 5. 校验 executionType 和 participantName 匹配
     *
     * @param userId          用户 ID
     * @param threadId        线程 ID
     * @param executionType   期望的执行类型
     * @param participantName 期望的参与者名称
     * @return 线程会话状态，不存在返回 Optional.empty()
     */
    public Optional<ThreadConversationState> load(String userId,
                                                   String threadId,
                                                   CheckpointExecutionType executionType,
                                                   String participantName) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(executionType, "executionType must not be null");
        Objects.requireNonNull(participantName, "participantName must not be null");

        if (userId.isBlank() || threadId.isBlank()) {
            return Optional.empty();
        }

        // loadLatestByThreadId
        Optional<AgentCheckpoint> cpOpt = checkpointStore.loadLatestByThreadId(
                userId, threadId, CheckpointPurpose.THREAD_MEMORY);

        if (cpOpt.isEmpty()) {
            return Optional.empty();
        }

        AgentCheckpoint cp = cpOpt.get();

        // 校验归属
        if (!userId.equals(cp.userId()) || !threadId.equals(cp.threadId())) {
            return Optional.empty();
        }

        // 校验 purpose=THREAD_MEMORY
        if (cp.purpose() != CheckpointPurpose.THREAD_MEMORY) {
            return Optional.empty();
        }

        // 校验 status=COMPLETED
        if (cp.status() != CheckpointStatus.COMPLETED) {
            return Optional.empty();
        }

        // 通过 mapper 恢复
        ThreadConversationState state = stateMapper.fromCheckpoint(cp);

        // 校验 executionType 匹配
        if (state.executionType() != executionType) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "ExecutionType mismatch: expected " + executionType
                            + ", stored " + state.executionType());
        }

        // 校验 participantName 匹配
        if (!participantName.equals(state.participantName())) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "ParticipantName mismatch: expected " + participantName
                            + ", stored " + state.participantName());
        }

        return Optional.of(state);
    }

    /**
     * 保存线程会话状态。
     * <p>
     * 流程：
     * 1. 校验输入
     * 2. 校验 state.lastCompletedRunId 等于 runId
     * 3. 生成 checkpointId
     * 4. purpose=THREAD_MEMORY, status=COMPLETED, pendingApproval=null
     * 5. 通过 CheckpointValidator 校验
     * 6. 保存新 Checkpoint
     * 7. 清理同 userId+threadId+THREAD_MEMORY 的旧 Checkpoint
     *
     * @param userId   用户 ID
     * @param threadId 线程 ID
     * @param runId    运行 ID
     * @param state    线程会话状态
     * @return 保存后的 Checkpoint
     */
    public AgentCheckpoint save(String userId,
                                String threadId,
                                String runId,
                                ThreadConversationState state) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(state, "state must not be null");

        if (userId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "userId must not be blank");
        }
        if (threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "threadId must not be blank");
        }
        if (runId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "runId must not be blank");
        }

        // 校验 state.lastCompletedRunId 等于 runId
        if (!runId.equals(state.lastCompletedRunId())) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "state.lastCompletedRunId does not match runId");
        }

        /*
         * 无论同一 runId 是否存在 HITL_RECOVERY，都创建一份新的
         * THREAD_MEMORY Checkpoint。
         *
         * HITL_RECOVERY 与 THREAD_MEMORY 使用不同 checkpointId，
         * 可以在保存与清理之间短暂同时存在。
         */
        String checkpointId = checkpointIdGenerator.generate();
        Instant now = clock.instant();

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                checkpointId,
                runId,
                threadId,
                userId,
                null,
                state.executionType(),
                CheckpointPurpose.THREAD_MEMORY,
                state.participantName(),
                "thread_memory",
                stateMapper.toStateData(state),
                null,
                CheckpointStatus.COMPLETED,
                0,
                now,
                now
        );

        checkpointValidator.validate(checkpoint);

        /*
         * 必须先完成 THREAD_MEMORY 保存。
         * HITL_RECOVERY 的清理由上层应用服务在确认保存成功后执行。
         */
        checkpointStore.save(checkpoint);

        log.info(
                "Thread memory saved: checkpointId={}, userId={}, "
                        + "threadId={}, runId={}, executionType={}",
                checkpointId,
                userId,
                threadId,
                runId,
                state.executionType());

        cleanupOldThreadMemory(
                userId,
                threadId,
                checkpointId);

        return checkpoint;
    }

    /**
     * 判断指定用户和线程是否存在活跃的 HITL 挂起运行。
     * <p>
     * 实现：
     * 1. 查询相同 userId 和 threadId 的 HITL_RECOVERY Checkpoint
     * 2. 仅 SUSPENDED 和 RESUMING 状态视为活跃
     * 3. COMPLETED 不视为活跃
     * 4. FAILED 不视为活跃
     * 5. 不查询 THREAD_MEMORY 作为 HITL 状态
     * 6. 不只按 threadId 查询
     * 7. 不访问 MemoryStore
     * 8. 返回确定 boolean
     * 9. 不删除任何 Checkpoint
     * 10. 不修改审批状态
     * <p>
     * 本批只使用该方法阻止普通 Agent 请求进入挂起线程。
     *
     * @param userId   用户 ID
     * @param threadId 线程 ID
     * @return true 表示存在活跃 HITL 运行
     */
    public boolean hasActiveHitlRun(String userId, String threadId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");

        if (userId.isBlank() || threadId.isBlank()) {
            return false;
        }

        // 查询相同 userId 和 threadId 的 HITL_RECOVERY Checkpoint
        List<AgentCheckpoint> hitlCheckpoints = checkpointStore.findByThreadId(
                userId, threadId, CheckpointPurpose.HITL_RECOVERY);

        for (AgentCheckpoint cp : hitlCheckpoints) {
            // 仅 SUSPENDED 和 RESUMING 状态视为活跃
            if (cp.status() == CheckpointStatus.SUSPENDED
                    || cp.status() == CheckpointStatus.RESUMING) {
                return true;
            }
        }

        return false;
    }

    /**
     * 清理同一线程的旧 THREAD_MEMORY Checkpoint。
     * <p>
     * 不得删除刚保存的新 Checkpoint。
     * 不得删除 HITL_RECOVERY。
     * 不得删除其他用户 Checkpoint。
     * 不得删除其他 threadId。
     * 清理旧记录失败时，新记录仍应可通过 loadLatest 加载。
     */
    private void cleanupOldThreadMemory(String userId, String threadId, String newCheckpointId) {
        try {
            List<AgentCheckpoint> existing = checkpointStore.findByThreadId(
                    userId, threadId, CheckpointPurpose.THREAD_MEMORY);

            for (AgentCheckpoint old : existing) {
                // 不删除刚保存的新 Checkpoint
                if (old.checkpointId().equals(newCheckpointId)) {
                    continue;
                }
                // 只删除同 runId 的旧记录（按 version 条件删除）
                checkpointStore.deleteIfVersionMatches(
                        old.runId(), old.checkpointId(), old.version());
            }
        } catch (Exception e) {
            // 清理旧记录失败时，新记录仍应可通过 loadLatest 加载
            log.warn("Failed to cleanup old THREAD_MEMORY: userId={}, threadId={}",
                    userId, threadId, e);
        }
    }
}
