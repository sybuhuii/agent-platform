package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.checkpoint.CheckpointResumeCoordinator;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointStateMapper;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import com.ksyun.agent.runtime.react.checkpoint.ReactResumeValidator;
import com.ksyun.agent.core.security.UserSession;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 恢复引擎，纯 Java 实现。
 * <p>
 * 完整流程：
 * 1. 加载 Checkpoint
 * 2. 校验恢复条件
 * 3. 原子抢占 SUSPENDED → RESUMING
 * 4. 重建 ReactAgentState
 * 5. 调用 compileForResume() 图
 * 6. 读取最终 AgentResult
 * 7. 更新 Checkpoint 生命周期（COMPLETED / FAILED / 重新 SUSPENDED）
 * <p>
 * 保证：
 * - 不使用 LangGraph4j CheckpointSaver
 * - 不使用 synchronized(runId.intern())
 * - 不把 SUSPENDED 转换成 FAILED
 * - 恢复使用原 runId 和 threadId
 * - 恢复不重新进入首次 Reason
 * - Checkpoint 抢占使用 version 条件更新
 * - 并发恢复只有一个成功
 * - 批准后真实工具只执行一次
 * - 拒绝后工具不执行，结果进入 Observe
 * - 恢复后再次危险操作能够重新挂起
 * - cursor 之前的工具不重复执行
 * - 完成后 Checkpoint 被清理
 * - 失败 Checkpoint 不会被再次恢复
 * - 不同用户不能操作对方 Checkpoint
 */
public class ReactResumeEngine {

    private static final Logger log = LoggerFactory.getLogger(ReactResumeEngine.class);

    private final CheckpointResumeCoordinator resumeCoordinator;
    private final ReactCheckpointStateMapper stateMapper;
    private final ReactCheckpointService checkpointService;
    private final CheckpointStore checkpointStore;
    private final CompiledGraph<ReactAgentState> resumeGraph;

    public ReactResumeEngine(
            CheckpointResumeCoordinator resumeCoordinator,
            ReactCheckpointStateMapper stateMapper,
            ReactCheckpointService checkpointService,
            CheckpointStore checkpointStore,
            ReactAgentGraphFactory graphFactory) {
        this.resumeCoordinator = Objects.requireNonNull(resumeCoordinator);
        this.stateMapper = Objects.requireNonNull(stateMapper);
        this.checkpointService = Objects.requireNonNull(checkpointService);
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.resumeGraph = graphFactory.compileForResume();
    }

    /**
     * 恢复执行。
     *
     * @param runId    运行 ID
     * @param operator 当前操作用户
     * @return Agent 执行结果
     */
    public AgentResult resume(String runId, UserSession operator) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        // 1. 原子抢占 SUSPENDED → RESUMING
        AgentCheckpoint resumingCheckpoint = resumeCoordinator.acquireForResume(runId, operator);

        log.info("Resuming ReAct execution: runId={}, checkpointId={}, approvalId={}, approvalStatus={}",
                runId, resumingCheckpoint.checkpointId(),
                resumingCheckpoint.pendingApproval().approvalId(),
                resumingCheckpoint.pendingApproval().status());

        // 2. 重建 ReactAgentState
        ReactAgentState resumeState = stateMapper.fromCheckpointForResume(resumingCheckpoint);

        // 3. 调用恢复图
        ReactAgentState finalState;
        try {
            finalState = resumeGraph.invoke(resumeState.data())
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Resume graph execution returned empty state"));
        } catch (AgentFrameworkException e) {
            log.error("Resume execution failed: runId={}, errorCode={}", runId, e.getErrorCode());
            // 更新 Checkpoint 为 FAILED
            updateCheckpointLifecycle(runId, CheckpointStatus.FAILED);
            throw e;
        } catch (Exception e) {
            log.error("Resume execution unexpected error: runId={}", runId, e);
            updateCheckpointLifecycle(runId, CheckpointStatus.FAILED);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Resume execution failed", e);
        }

        // 4. 读取最终结果
        AgentResult finalResult = getFinalResult(finalState);

        // 5. 更新 Checkpoint 生命周期
        if (finalResult != null) {
            if (finalResult.status() == com.ksyun.agent.core.run.RunStatus.SUSPENDED) {
                // 恢复后再次挂起（多危险工具场景）
                // ReactCheckpointService.suspend 已在 ToolExecutionNode 中保存了新 Checkpoint
                log.info("Resume resulted in re-suspension: runId={}", runId);
            } else if (finalResult.success()) {
                updateCheckpointLifecycle(runId, CheckpointStatus.COMPLETED);
            } else {
                updateCheckpointLifecycle(runId, CheckpointStatus.FAILED);
            }
        }

        if (finalResult == null) {
            log.error("Resume execution completed without finalResult: runId={}", runId);
            updateCheckpointLifecycle(runId, CheckpointStatus.FAILED);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Resume execution completed without result");
        }

        return finalResult;
    }

    /**
     * 更新 Checkpoint 生命周期状态。
     * <p>
     * 完成后 Checkpoint 被清理（删除）。
     * 失败 Checkpoint 保留（用于排查，不会被再次恢复）。
     */
    private void updateCheckpointLifecycle(String runId, CheckpointStatus newStatus) {
        try {
            if (newStatus == CheckpointStatus.COMPLETED) {
                // 完成后删除 Checkpoint
                checkpointStore.delete(runId);
                log.info("Checkpoint cleaned up after completion: runId={}", runId);
            } else {
                // 失败保留 Checkpoint，更新状态
                checkpointStore.load(runId).ifPresent(cp -> {
                    AgentCheckpoint updatedCp = new AgentCheckpoint(
                            cp.checkpointId(),
                            cp.runId(),
                            cp.threadId(),
                            cp.userId(),
                            cp.sessionId(),
                            cp.executionType(),
                            cp.agentName(),
                            cp.nodeName(),
                            cp.stateData(),
                            null, // 失败时清除 approval
                            newStatus,
                            cp.version() + 1,
                            cp.createdAt(),
                            java.time.Instant.now()
                    );
                    checkpointStore.updateIfVersionMatches(updatedCp, cp.version());
                });
                log.info("Checkpoint updated to {}: runId={}", newStatus, runId);
            }
        } catch (Exception e) {
            // 生命周期更新失败不影响主结果
            log.warn("Failed to update checkpoint lifecycle: runId={}, status={}", runId, newStatus, e);
        }
    }

    private AgentResult getFinalResult(ReactAgentState state) {
        return state.<AgentResult>value(FINAL_RESULT).orElse(null);
    }
}
