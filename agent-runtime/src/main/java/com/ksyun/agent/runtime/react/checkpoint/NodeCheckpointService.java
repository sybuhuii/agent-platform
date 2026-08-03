package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.approval.NodeInterruptRequest;
import com.ksyun.agent.core.approval.OperationType;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.react.ReactStopReason;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 保存 OperationType.NODE 的 ReAct Checkpoint。 */
public final class NodeCheckpointService {

    private final CheckpointStore checkpointStore;
    private final CheckpointIdGenerator checkpointIdGenerator;
    private final CheckpointValidator checkpointValidator;
    private final Clock clock;

    public NodeCheckpointService(
            CheckpointStore checkpointStore,
            CheckpointIdGenerator checkpointIdGenerator,
            CheckpointValidator checkpointValidator,
            Clock clock) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.checkpointIdGenerator = Objects.requireNonNull(checkpointIdGenerator);
        this.checkpointValidator = Objects.requireNonNull(checkpointValidator);
        this.clock = Objects.requireNonNull(clock);
    }

    public AgentCheckpoint suspend(
            ReactAgentState state,
            NodeInterruptRequest request,
            PendingApproval pendingApproval) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(pendingApproval, "pendingApproval must not be null");

        validateRequest(request, pendingApproval);

        RunContext runContext = ReactStateKeys.getRunContext(state);
        AgentDefinition definition = ReactStateKeys.getAgentDefinition(state);
        AgentCheckpoint existing = checkpointStore.load(runContext.runId()).orElse(null);

        if (existing == null) {
            return createCheckpoint(
                    state, request, pendingApproval, runContext, definition);
        }
        return updateForNewSuspension(
                existing, state, request, pendingApproval, runContext, definition);
    }

    private AgentCheckpoint createCheckpoint(
            ReactAgentState state,
            NodeInterruptRequest request,
            PendingApproval pendingApproval,
            RunContext runContext,
            AgentDefinition definition) {
        String checkpointId = checkpointIdGenerator.generate();
        Instant now = clock.instant();
        Map<String, Object> stateData = buildStateData(
                state, request, pendingApproval, checkpointId);

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                checkpointId,
                runContext.runId(),
                runContext.threadId(),
                runContext.userId(),
                runContext.sessionId(),
                CheckpointExecutionType.REACT_AGENT,
                CheckpointPurpose.HITL_RECOVERY,
                definition.name(),
                request.nodeName(),
                stateData,
                pendingApproval,
                CheckpointStatus.SUSPENDED,
                0,
                now,
                now
        );

        checkpointValidator.validate(checkpoint);
        checkpointStore.save(checkpoint);
        return checkpoint;
    }

    private AgentCheckpoint updateForNewSuspension(
            AgentCheckpoint existing,
            ReactAgentState state,
            NodeInterruptRequest request,
            PendingApproval pendingApproval,
            RunContext runContext,
            AgentDefinition definition) {
        if (existing.pendingApproval() != null
                && existing.pendingApproval().approvalId()
                .equals(pendingApproval.approvalId())) {
            return existing;
        }
        if (existing.status() != CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Cannot suspend node while checkpoint status is " + existing.status());
        }
        if (existing.purpose() != CheckpointPurpose.HITL_RECOVERY
                || !existing.userId().equals(runContext.userId())
                || !existing.threadId().equals(runContext.threadId())
                || !existing.agentName().equals(definition.name())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Existing checkpoint does not belong to the current execution");
        }

        long expectedVersion = existing.version();
        Instant now = clock.instant();
        Map<String, Object> stateData = buildStateData(
                state, request, pendingApproval, existing.checkpointId());

        AgentCheckpoint updated = new AgentCheckpoint(
                existing.checkpointId(),
                existing.runId(),
                existing.threadId(),
                existing.userId(),
                existing.sessionId(),
                existing.executionType(),
                existing.purpose(),
                existing.agentName(),
                request.nodeName(),
                stateData,
                pendingApproval,
                CheckpointStatus.SUSPENDED,
                expectedVersion + 1,
                existing.createdAt(),
                now
        );

        checkpointValidator.validate(updated);
        if (!checkpointStore.updateIfVersionMatches(updated, expectedVersion)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Checkpoint changed during node suspension");
        }
        return updated;
    }

    private Map<String, Object> buildStateData(
            ReactAgentState state,
            NodeInterruptRequest request,
            PendingApproval pendingApproval,
            String checkpointId) {
        Map<String, Object> stateData = new HashMap<>(state.data());
        stateData.put(ReactStateKeys.NODE_RESUME_HANDLER_KEY, request.resumeHandlerKey());
        stateData.put(ReactStateKeys.NODE_RESUME_DATA, request.resumeData());
        stateData.put(ReactStateKeys.PENDING_APPROVAL, pendingApproval);
        stateData.put(ReactStateKeys.CHECKPOINT_ID, checkpointId);
        stateData.put(ReactStateKeys.RUN_STATUS, RunStatus.SUSPENDED);
        stateData.put(ReactStateKeys.STOP_REASON, ReactStopReason.SUSPENDED);
        return stateData;
    }

    private void validateRequest(
            NodeInterruptRequest request,
            PendingApproval pendingApproval) {
        if (pendingApproval.payload().operationType() != OperationType.NODE) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "NodeCheckpointService only accepts NODE approval");
        }
        if (!request.nodeName().equals(pendingApproval.payload().nodeName())
                || !request.operationName()
                .equals(pendingApproval.payload().operationName())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Node approval does not match interrupt request");
        }
    }
}