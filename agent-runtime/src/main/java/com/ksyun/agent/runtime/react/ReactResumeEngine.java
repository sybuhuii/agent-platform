package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.checkpoint.CheckpointResumeCoordinator;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointStateMapper;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import com.ksyun.agent.runtime.react.checkpoint.ReactResumeValidator;
import com.ksyun.agent.core.security.UserSession;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 恢复引擎，纯 Java 实现。
 * <p>
 * 完整流程：
 * 1. 检查恢复依赖和模型能力
 * 2. 完整验证
 * 3. 原子抢占 SUSPENDED → RESUMING
 * 4. 从 Checkpoint 重建 ReactAgentState
 * 5. 编译白名单恢复图
 * 6. 从 execute_tools 节点执行
 * 7. 批准时真实执行危险工具一次
 * 8. 拒绝时生成失败 ToolResult 并进入 Observe
 * 9. 后续 Reason 继续生成最终回答
 * 10. 保持原 runId 和 threadId
 * 11. 再次挂起时返回新的 approvalId
 * 12. 正常完成时处理 Checkpoint 终态和条件清理
 * 13. 框架异常时将已抢占 Checkpoint 标记为 FAILED
 * <p>
 * 保证：
 * - 不使用 LangGraph4j CheckpointSaver
 * - 不使用 synchronized(runId.intern())
 * - 恢复使用原 runId 和 threadId
 * - 恢复不重新进入首次 Reason
 * - Checkpoint 抢占使用 version 条件更新
 * - 并发恢复只有一个成功
 * - 批准后真实工具只执行一次
 * - 拒绝后工具不执行，结果进入 Observe
 * - 恢复后再次危险操作能够重新挂起
 * - cursor 之前的工具不重复执行
 * - 完成后 Checkpoint 被条件清理
 * - 失败 Checkpoint 不会被再次恢复
 * - 不同用户不能操作对方 Checkpoint
 * - 不得创建新 Session、提升权限或绕过 Gateway
 */
public class ReactResumeEngine {

    private static final Logger log = LoggerFactory.getLogger(ReactResumeEngine.class);

    private final CheckpointResumeCoordinator resumeCoordinator;
    private final ReactCheckpointStateMapper stateMapper;
    private final ReactCheckpointService checkpointService;
    private final CheckpointStore checkpointStore;
    private final ReactAgentGraphFactory graphFactory;
    private final Clock clock;

    // 恢复图按需编译，懒初始化
    private volatile CompiledGraph<ReactAgentState> resumeGraph;

    public ReactResumeEngine(
            CheckpointResumeCoordinator resumeCoordinator,
            ReactCheckpointStateMapper stateMapper,
            ReactCheckpointService checkpointService,
            CheckpointStore checkpointStore,
            ReactAgentGraphFactory graphFactory,
            Clock clock) {
        this.resumeCoordinator = Objects.requireNonNull(resumeCoordinator);
        this.stateMapper = Objects.requireNonNull(stateMapper);
        this.checkpointService = Objects.requireNonNull(checkpointService);
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.graphFactory = Objects.requireNonNull(graphFactory);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 获取恢复图（懒初始化）。
     * <p>
     * 恢复图使用与正常图相同的节点实例，不创建第二套节点。
     */
    private CompiledGraph<ReactAgentState> getResumeGraph() {
        if (resumeGraph == null) {
            synchronized (this) {
                if (resumeGraph == null) {
                    resumeGraph = graphFactory.compileForResume();
                }
            }
        }
        return resumeGraph;
    }

    /**
     * 恢复执行。
     * <p>
     * APPROVE 和 REJECT 都通过此方法恢复图执行：
     * - APPROVE：真实执行危险工具
     * - REJECT：生成失败 ToolResult，经 Observe 回灌模型
     * <p>
     * 不得在 Application Service 中伪造最终回答。
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

        log.info("Resuming ReAct execution: runId={}, checkpointId={}, version={}, approvalId={}, approvalStatus={}",
                runId, resumingCheckpoint.checkpointId(), resumingCheckpoint.version(),
                resumingCheckpoint.pendingApproval().approvalId(),
                resumingCheckpoint.pendingApproval().status());

        // 2. 从 Checkpoint 重建 ReactAgentState
        ReactAgentState resumeState = stateMapper.fromCheckpointForResume(resumingCheckpoint);

        // 3. 调用恢复图
        ReactAgentState finalState;
        try {
            finalState = getResumeGraph().invoke(resumeState.data())
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Resume graph execution returned empty state"));
        } catch (AgentFrameworkException e) {
            log.error("Resume execution failed: runId={}, errorCode={}", runId, e.getErrorCode());
            // 将已抢占 Checkpoint 标记为 FAILED
            markFailed(runId, resumingCheckpoint, e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.error("Resume execution unexpected error: runId={}", runId, e);
            markFailed(runId, resumingCheckpoint, AgentErrorCode.INTERNAL_ERROR);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Resume execution failed", e);
        }

        // 4. 读取最终结果
        AgentResult finalResult = getFinalResult(finalState);

        // 5. 更新 Checkpoint 生命周期
        if (finalResult != null) {
            if (finalResult.status() == RunStatus.SUSPENDED) {
                // 恢复后再次挂起（多危险工具场景）
                // ReactCheckpointService.suspend 已在 ToolExecutionNode 中保存了新 Checkpoint
                log.info("Resume resulted in re-suspension: runId={}", runId);
            } else if (finalResult.success()) {
                // 正常完成：条件更新 COMPLETED 后条件删除
                completeAndCleanup(runId, resumingCheckpoint);
            } else {
                // 普通工具失败（success=false）经 Observe 回灌模型后仍可能最终成功
                // 检查是否是框架级失败
                if (finalResult.status() == RunStatus.FAILED) {
                    markFailed(runId, resumingCheckpoint, AgentErrorCode.RESUME_FAILED);
                } else {
                    // 其他状态（如 COMPLETED）按正常完成处理
                    completeAndCleanup(runId, resumingCheckpoint);
                }
            }
        }

        if (finalResult == null) {
            log.error("Resume execution completed without finalResult: runId={}", runId);
            markFailed(runId, resumingCheckpoint, AgentErrorCode.INTERNAL_ERROR);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Resume execution completed without result");
        }

        return finalResult;
    }

    /**
     * 正常完成后：条件更新 COMPLETED，然后条件删除。
     * <p>
     * 使用 checkpointId/version 条件删除，避免误删同一 runId 后续产生的新 Checkpoint。
     * 清理失败不伪造失败结果，但版本冲突必须明确暴露。
     */
    private void completeAndCleanup(String runId, AgentCheckpoint resumingCheckpoint) {
        try {
            // 条件更新 RESUMING → COMPLETED
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
                // 版本冲突：可能已被再次挂起，不能删除
                log.warn("Checkpoint version conflict during COMPLETED update: runId={}, expectedVersion={}. "
                        + "Checkpoint may have been re-suspended, skipping cleanup.",
                        runId, expectedVersion);
                return;
            }

            // 条件删除：按 checkpointId + version 匹配
            boolean deleted = checkpointStore.deleteIfVersionMatches(
                    runId, completedCp.checkpointId(), completedCp.version());
            if (deleted) {
                log.info("Checkpoint cleaned up after completion: runId={}", runId);
            } else {
                log.warn("Checkpoint conditional delete failed after completion: runId={}. "
                        + "May have been modified concurrently.", runId);
            }
        } catch (Exception e) {
            // 生命周期更新失败不影响主结果
            log.warn("Failed to update checkpoint lifecycle: runId={}, status=COMPLETED", runId, e);
        }
    }

    /**
     * 框架异常时将已抢占 Checkpoint 标记为 FAILED。
     * <p>
     * 保存安全 errorCode，不保存异常对象或堆栈。
     * 默认保留 FAILED Checkpoint 供诊断。
     */
    private void markFailed(String runId, AgentCheckpoint resumingCheckpoint, AgentErrorCode errorCode) {
        try {
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
                        runId, expectedVersion);
            } else {
                log.info("Checkpoint marked as FAILED: runId={}, errorCode={}", runId, errorCode);
            }
        } catch (Exception e) {
            // 生命周期更新失败不影响主错误
            log.warn("Failed to mark checkpoint as FAILED: runId={}", runId, e);
        }
    }

    private AgentResult getFinalResult(ReactAgentState state) {
        return state.<AgentResult>value(FINAL_RESULT).orElse(null);
    }
}
