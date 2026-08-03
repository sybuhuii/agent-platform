package com.ksyun.agent.runtime.hitl;

import com.ksyun.agent.core.approval.ApprovalAction;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.HumanApprovalGateway;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.PendingResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * LangChain4j Agentic HumanInTheLoop 适配器。
 *
 * LangChain4j 类型全部收口在本类和 workflow 接口中。
 */
public final class LangChain4jHumanApprovalGateway implements HumanApprovalGateway {

    private static final String DECISION_KEY = "humanDecision";
    private static final Duration REGISTRATION_TIMEOUT = Duration.ofSeconds(3);

    private final ExecutorService executor;
    private final LangChain4jApprovalWorkflow workflow;
    private final ConcurrentMap<String, CompletableFuture<Void>> executions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> decisionLocks = new ConcurrentHashMap<>();

    public LangChain4jHumanApprovalGateway(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");

        HumanInTheLoop humanInTheLoop = AgenticServices.humanInTheLoopBuilder()
                .description("Wait for an authenticated human approval decision")
                .outputKey(DECISION_KEY)
                .async(true)
                .responseProvider(scope -> {
                    String approvalId = scope.readState("approvalId", "");
                    if (approvalId.isBlank()) {
                        throw new AgentFrameworkException(
                                AgentErrorCode.INTERNAL_ERROR,
                                "HITL approvalId is missing");
                    }
                    return new PendingResponse<String>(approvalId);
                })
                .build();

        this.workflow = AgenticServices.sequenceBuilder(LangChain4jApprovalWorkflow.class)
                .subAgents(humanInTheLoop)
                .build();
    }

    @Override
    public void interrupt(PendingApproval pendingApproval) {
        validatePending(pendingApproval);
        String approvalId = pendingApproval.approvalId();

        AgenticScope currentScope = workflow.getAgenticScope(approvalId);
        if (currentScope != null && currentScope.pendingResponseIds().contains(approvalId)) {
            return;
        }

        CompletableFuture<Void> execution = executions.computeIfAbsent(
                approvalId,
                ignored -> CompletableFuture.runAsync(
                        () -> workflow.awaitDecision(approvalId, approvalId),
                        executor));

        long deadline = System.nanoTime() + REGISTRATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AgenticScope scope = workflow.getAgenticScope(approvalId);
            if (scope != null && scope.pendingResponseIds().contains(approvalId)) {
                return;
            }
            if (execution.isCompletedExceptionally()) {
                throw workflowFailure(approvalId, execution);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new AgentFrameworkException(
                        AgentErrorCode.INTERNAL_ERROR,
                        "Interrupted while registering HITL response");
            }
        }

        release(approvalId);
        throw new AgentFrameworkException(
                AgentErrorCode.INTERNAL_ERROR,
                "Timed out while registering HITL response");
    }

    @Override
    public void resume(PendingApproval approval, ApprovalAction action) {
        Objects.requireNonNull(approval, "pendingApproval must not be null");
        Objects.requireNonNull(action, "action must not be null");
        String approvalId = approval.approvalId();
        Object decisionLock = decisionLocks.computeIfAbsent(approvalId, ignored -> new Object());
        synchronized (decisionLock) {
            resumeLocked(approval, action, approvalId);
        }
    }

    private void resumeLocked(
            PendingApproval approval,
            ApprovalAction action,
            String approvalId) {

        AgenticScope scope = workflow.getAgenticScope(approvalId);
        if (scope != null) {
            Object completedValue = scope.state().get(DECISION_KEY);
            if (completedValue instanceof String existingDecision) {
                ensureSameAction(
                        approvalId,
                        ApprovalAction.valueOf(existingDecision),
                        action);
                return;
            }
        }

        if (scope == null || !scope.pendingResponseIds().contains(approvalId)) {
            // 决定已经写入 Checkpoint 但进程内 AgenticScope 丢失时，
            // 只重建 LangChain4j 等待点，不重新执行 Agent 节点。
            PendingApproval pendingView = approval.status() == ApprovalStatus.PENDING
                    ? approval
                    : new PendingApproval(
                    approval.payload(),
                    ApprovalStatus.PENDING,
                    null,
                    approval.createdAt(),
                    approval.updatedAt());
            interrupt(pendingView);
            scope = workflow.getAgenticScope(approvalId);
        }

        if (scope == null || !scope.completePendingResponse(approvalId, action.name())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "HITL response is no longer pending");
        }
    }

    @Override
    public void release(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            return;
        }
        workflow.evictAgenticScope(approvalId);
        CompletableFuture<Void> execution = executions.remove(approvalId);
        if (execution != null && !execution.isDone()) {
            execution.cancel(true);
        }
        decisionLocks.remove(approvalId);
    }

    private void validatePending(PendingApproval pendingApproval) {
        Objects.requireNonNull(pendingApproval, "pendingApproval must not be null");
        if (pendingApproval.status() != ApprovalStatus.PENDING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Only PENDING approval can register a HITL response");
        }
    }

    private void ensureSameAction(
            String approvalId,
            ApprovalAction existing,
            ApprovalAction requested) {
        if (existing != requested) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_ALREADY_DECIDED,
                    "Approval " + approvalId + " was already decided");
        }
    }

    private AgentFrameworkException workflowFailure(
            String approvalId,
            CompletableFuture<Void> execution) {
        try {
            execution.join();
            return new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "HITL workflow ended before response registration");
        } catch (RuntimeException exception) {
            return new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to register HITL response for approval " + approvalId,
                    exception);
        }
    }
}
