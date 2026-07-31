package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorChildExecutionStatus;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.react.ThreadExecutionOutcome;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorCheckpointService;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorCheckpointStateMapper;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * Supervisor 恢复引擎，纯 Java 实现。
 * <p>
 * 不添加 Spring 注解。
 * <p>
 * 完整流程：
 * 1. 从父 Checkpoint 恢复 SupervisorAgentState
 * 2. 从 dispatchTasks 中找到 SUSPENDED 子任务
 * 3. 调用 ReactResumeEngine 恢复每个暂停子 Agent
 * 4. 更新 dispatchTasks 中对应子任务的状态
 * 5. 编译恢复图，从 DISPATCH_AGENTS 节点继续执行
 * 6. 处理父 Checkpoint 生命周期
 * <p>
 * 约束：
 * - 不在共享图实例中保存请求 State
 * - 不跨请求复用可变 SupervisorAgentState
 * - 不自行实现 Supervisor 循环
 * - 不修改子 Agent Checkpoint
 * - 保持原 runId 和 threadId
 * - 不生成新的 runId
 * - 不直接访问 MemoryStore
 */
public class SupervisorResumeEngine {

    private static final Logger log = LoggerFactory.getLogger(SupervisorResumeEngine.class);

    private final SupervisorCheckpointService checkpointService;
    private final SupervisorCheckpointStateMapper stateMapper;
    private final SupervisorGraphFactory graphFactory;
    private final SupervisorThreadConversationStateMapper threadStateMapper;
    private final SupervisorThreadPersistencePolicy persistencePolicy;
    private final com.ksyun.agent.runtime.react.ReactResumeEngine reactResumeEngine;
    private final Clock clock;

    public SupervisorResumeEngine(
            SupervisorCheckpointService checkpointService,
            SupervisorCheckpointStateMapper stateMapper,
            SupervisorGraphFactory graphFactory,
            SupervisorThreadConversationStateMapper threadStateMapper,
            SupervisorThreadPersistencePolicy persistencePolicy,
            com.ksyun.agent.runtime.react.ReactResumeEngine reactResumeEngine,
            Clock clock) {
        this.checkpointService = Objects.requireNonNull(checkpointService);
        this.stateMapper = Objects.requireNonNull(stateMapper);
        this.graphFactory = Objects.requireNonNull(graphFactory);
        this.threadStateMapper = Objects.requireNonNull(threadStateMapper);
        this.persistencePolicy = Objects.requireNonNull(persistencePolicy);
        this.reactResumeEngine = Objects.requireNonNull(reactResumeEngine);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 恢复 Supervisor 执行。
     * <p>
     * 流程：
     * 1. 原子抢占父 Checkpoint SUSPENDED → RESUMING
     * 2. 从父 Checkpoint 恢复 SupervisorAgentState
     * 3. 找到 dispatchTasks 中的 SUSPENDED 子任务
     * 4. 对每个 SUSPENDED 子任务调用 ReactResumeEngine.resumeThread
     * 5. 更新 dispatchTasks 中子任务状态
     * 6. 编译恢复图并从 DISPATCH_AGENTS 继续执行
     * 7. 处理 Checkpoint 生命周期
     * 8. 返回线程执行结果
     *
     * @param parentRunId 父 Supervisor runId
     * @param operator    当前操作用户
     * @return 线程执行结果
     */
    public ThreadExecutionOutcome resumeSupervisor(String parentRunId, UserSession operator) {
        Objects.requireNonNull(parentRunId, "parentRunId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        // 1. 原子抢占父 Checkpoint
        AgentCheckpoint resumingCheckpoint = checkpointService.acquireForResume(parentRunId, operator);

        log.info("Resuming Supervisor execution: parentRunId={}, checkpointId={}, version={}",
                parentRunId, resumingCheckpoint.checkpointId(), resumingCheckpoint.version());

        // 2. 从父 Checkpoint 恢复 SupervisorAgentState
        SupervisorAgentState resumeState = stateMapper.fromCheckpointForResume(resumingCheckpoint);

        // 3-5. 恢复暂停子 Agent 并更新 dispatchTasks
        resumeSuspendedChildren(resumeState, operator);

        // 6. 编译恢复图并执行
        AgentResult finalResult;
        SupervisorAgentState finalState;
        try {
            CompiledGraph<SupervisorAgentState> resumeGraph = graphFactory.buildResumeGraph();

            finalState = resumeGraph.invoke(resumeState.data())
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Supervisor resume graph execution returned empty state"));

            finalResult = getFinalResult(finalState);
            if (finalResult == null) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INTERNAL_ERROR,
                        "Supervisor resume execution completed without finalResult");
            }
        } catch (AgentFrameworkException e) {
            log.error("Supervisor resume execution failed: parentRunId={}, errorCode={}",
                    parentRunId, e.getErrorCode());
            try {
                checkpointService.fail(resumingCheckpoint, e.getErrorCode());
            } catch (AgentFrameworkException lifecycleEx) {
                log.warn("Supervisor checkpoint lifecycle fail also conflicted: parentRunId={}", parentRunId);
            }
            throw e;
        } catch (Exception e) {
            log.error("Supervisor resume execution unexpected error: parentRunId={}", parentRunId, e);
            try {
                checkpointService.fail(resumingCheckpoint, AgentErrorCode.INTERNAL_ERROR);
            } catch (AgentFrameworkException lifecycleEx) {
                log.warn("Supervisor checkpoint lifecycle fail also conflicted: parentRunId={}", parentRunId);
            }
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor resume execution failed", e);
        }

        // 7. 处理 Checkpoint 生命周期
        handleCheckpointLifecycle(finalResult, resumingCheckpoint);

        // 8. 判断是否可提取稳定线程状态
        Optional<ThreadConversationState> conversationState = Optional.empty();
        if (persistencePolicy.isPersistable(finalResult, finalState)) {
            try {
                conversationState = Optional.of(
                        threadStateMapper.extractStableState(
                                finalResult.agentName(),
                                parentRunId,
                                finalState,
                                Instant.now(clock)
                        )
                );
            } catch (AgentFrameworkException e) {
                log.error("Supervisor resume stable state extraction failed: parentRunId={}", parentRunId, e);
                throw e;
            }
        }

        return new ThreadExecutionOutcome(finalResult, conversationState);
    }

    /**
     * 恢复暂停子 Agent 并更新 SupervisorAgentState 中的 dispatchTasks。
     * <p>
     * 对每个 SUSPENDED 子任务：
     * 1. 通过 ReactResumeEngine.resumeThread 恢复子 Agent
     * 2. 根据子 Agent 恢复结果更新 dispatchTasks 中的状态
     * 3. 更新 SUSPENDED_CHILDREN
     */
    private void resumeSuspendedChildren(SupervisorAgentState state, UserSession operator) {
        List<SupervisorChildExecution> dispatchTasks = getDispatchTasks(state);
        List<SupervisorChildExecution> suspendedChildren = getSuspendedChildren(state);

        if (suspendedChildren.isEmpty()) {
            return;
        }

        // 对每个 SUSPENDED 子任务调用 ReactResumeEngine
        for (SupervisorChildExecution suspended : suspendedChildren) {
            String childRunId = suspended.runLink().childRunId();

            log.info("Resuming suspended child agent: childRunId={}, approvalId={}, dispatchIndex={}",
                    childRunId, suspended.approvalId(), suspended.dispatchIndex());

            try {
                ThreadExecutionOutcome childOutcome = reactResumeEngine.resumeThread(childRunId, operator);
                AgentResult childResult = childOutcome.result();

                // 更新 dispatchTasks 中对应子任务状态
                updateChildExecutionStatus(dispatchTasks, suspended, childResult);

            } catch (AgentFrameworkException e) {
                log.error("Failed to resume child agent: childRunId={}, errorCode={}", childRunId, e.getErrorCode());
                // 子 Agent 恢复失败，标记为 FAILED
                AgentResult failureResult = AgentResult.failure(
                        suspended.task().agentName(),
                        e.getErrorCode().name(),
                        "Child agent resume failed");
                updateChildExecutionStatus(dispatchTasks, suspended, failureResult);
            }
        }

        // 清空 SUSPENDED_CHILDREN（所有子任务已恢复）
        // dispatchTasks 中的更新通过 Channel 覆盖语义传播
        state.data().put(SUSPENDED_CHILDREN, List.of());
    }

    /**
     * 更新 dispatchTasks 中暂停子任务的状态。
     */
    private void updateChildExecutionStatus(
            List<SupervisorChildExecution> dispatchTasks,
            SupervisorChildExecution suspended,
            AgentResult childResult) {

        for (int i = 0; i < dispatchTasks.size(); i++) {
            SupervisorChildExecution exec = dispatchTasks.get(i);
            if (exec.dispatchIndex() == suspended.dispatchIndex()
                    && exec.task().taskId().equals(suspended.task().taskId())) {

                RunStatus runStatus = childResult.status();
                if (runStatus == null) {
                    runStatus = childResult.success() ? RunStatus.COMPLETED : RunStatus.FAILED;
                }

                SupervisorChildExecution updated = switch (runStatus) {
                    case COMPLETED -> SupervisorChildExecution.completed(
                            exec.task(), exec.dispatchIndex(), exec.runLink(), childResult);
                    case FAILED -> SupervisorChildExecution.failed(
                            exec.task(), exec.dispatchIndex(), exec.runLink(), childResult);
                    case SUSPENDED -> {
                        // 子 Agent 再次暂停
                        String newApprovalId = extractApprovalId(childResult);
                        yield SupervisorChildExecution.suspended(
                                exec.task(), exec.dispatchIndex(), exec.runLink(), childResult, newApprovalId);
                    }
                    default -> SupervisorChildExecution.failed(
                            exec.task(), exec.dispatchIndex(), exec.runLink(), childResult);
                };

                dispatchTasks.set(i, updated);
                break;
            }
        }
    }

    /**
     * 处理父 Checkpoint 生命周期。
     * <p>
     * COMPLETED：标记完成并清理
     * FAILED：标记失败
     * SUSPENDED：由 Dispatch 保存新 Checkpoint 处理
     */
    private void handleCheckpointLifecycle(AgentResult finalResult, AgentCheckpoint resumingCheckpoint) {
        if (finalResult.status() == RunStatus.COMPLETED) {
            try {
                checkpointService.complete(resumingCheckpoint);
            } catch (AgentFrameworkException e) {
                log.warn("Supervisor checkpoint complete conflicted: runId={}", resumingCheckpoint.runId());
            }
        } else if (finalResult.status() == RunStatus.FAILED) {
            try {
                checkpointService.fail(resumingCheckpoint, AgentErrorCode.RESUME_FAILED);
            } catch (AgentFrameworkException e) {
                log.warn("Supervisor checkpoint fail conflicted: runId={}", resumingCheckpoint.runId());
            }
        }
        // SUSPENDED：由 Dispatch 中 saveParentCheckpoint 的新 Checkpoint 处理
        // 旧 RESUMING Checkpoint 保持不变，第三步 SupervisorCheckpointService.handleExistingCheckpoint 会处理
    }

    private String extractApprovalId(AgentResult result) {
        Object approvalIdObj = result.metadata().get("approvalId");
        if (approvalIdObj == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                    "SUSPENDED result missing approvalId in metadata");
        }
        String approvalId = approvalIdObj.toString();
        if (approvalId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                    "SUSPENDED result has blank approvalId in metadata");
        }
        return approvalId;
    }

    private AgentResult getFinalResult(SupervisorAgentState state) {
        return state.<AgentResult>value(FINAL_RESULT).orElse(null);
    }
}
