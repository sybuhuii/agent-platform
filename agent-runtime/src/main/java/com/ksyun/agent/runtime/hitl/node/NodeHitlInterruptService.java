package com.ksyun.agent.runtime.hitl.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.approval.ApprovalIdGenerator;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.HumanApprovalGateway;
import com.ksyun.agent.core.approval.InterruptPayload;
import com.ksyun.agent.core.approval.NodeInterruptRequest;
import com.ksyun.agent.core.approval.OperationType;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.react.checkpoint.NodeCheckpointService;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

/**
 * 通用节点 HITL 薄适配器。
 *
 * 节点自己判断是否需要人工介入；本类只负责创建安全审批请求、
 * 调用 LangChain4j HumanInTheLoop，并保存节点 Checkpoint。
 */
public final class NodeHitlInterruptService {

    private final ApprovalIdGenerator approvalIdGenerator;
    private final HumanApprovalGateway humanApprovalGateway;
    private final NodeCheckpointService checkpointService;
    private final SensitiveValueSanitizer sanitizer;
    private final Clock clock;

    public NodeHitlInterruptService(
            ApprovalIdGenerator approvalIdGenerator,
            HumanApprovalGateway humanApprovalGateway,
            NodeCheckpointService checkpointService,
            SensitiveValueSanitizer sanitizer,
            Clock clock) {
        this.approvalIdGenerator = Objects.requireNonNull(approvalIdGenerator);
        this.humanApprovalGateway = Objects.requireNonNull(humanApprovalGateway);
        this.checkpointService = Objects.requireNonNull(checkpointService);
        this.sanitizer = Objects.requireNonNull(sanitizer);
        this.clock = Objects.requireNonNull(clock);
    }

    public Map<String, Object> suspend(
            ReactAgentState state,
            NodeInterruptRequest request) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(request, "request must not be null");

        RunContext runContext = ReactStateKeys.getRunContext(state);
        AgentDefinition definition = ReactStateKeys.getAgentDefinition(state);
        Instant now = clock.instant();
        String approvalId = approvalIdGenerator.generate();

        Map<String, Object> safePayload = sanitizer.sanitize(request.safePayload());
        InterruptPayload payload = new InterruptPayload(
                approvalId,
                runContext.runId(),
                runContext.threadId(),
                runContext.userId(),
                definition.name(),
                request.nodeName(),
                request.reason(),
                OperationType.NODE,
                request.operationName(),
                safePayload,
                request.riskLevel(),
                now,
                null,
                null
        );

        PendingApproval pendingApproval = new PendingApproval(
                payload,
                ApprovalStatus.PENDING,
                null,
                now,
                now
        );

        // HumanInTheLoop 是人工等待的唯一原语。
        humanApprovalGateway.interrupt(pendingApproval);
        try {
            var checkpoint = checkpointService.suspend(
                    state,
                    request,
                    pendingApproval
            );
            Map<String, Object> delta = new HashMap<>();
            delta.put(ReactStateKeys.NODE_RESUME_HANDLER_KEY, request.resumeHandlerKey());
            delta.put(ReactStateKeys.NODE_RESUME_DATA, request.resumeData());
            delta.put(ReactStateKeys.PENDING_APPROVAL, pendingApproval);
            delta.put(ReactStateKeys.CHECKPOINT_ID, checkpoint.checkpointId());
            delta.put(ReactStateKeys.RUN_STATUS, RunStatus.SUSPENDED);
            delta.put(ReactStateKeys.STOP_REASON,
                    com.ksyun.agent.runtime.react.ReactStopReason.SUSPENDED);
            return Map.copyOf(delta);
        } catch (RuntimeException exception) {
            // Checkpoint 未保存成功时，不能留下无主 AgenticScope。
            humanApprovalGateway.release(approvalId);
            throw exception;
        }
    }
}
