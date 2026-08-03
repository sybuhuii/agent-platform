package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.approval.ApprovalIdGenerator;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.InterruptPayload;
import com.ksyun.agent.core.approval.OperationType;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.tool.ToolExecutionChain;
import com.ksyun.agent.runtime.tool.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ksyun.agent.core.approval.HumanApprovalGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具审批拦截器。
 * <p>
 * 依赖：ToolRegistry、ToolApprovalPolicy、ApprovalIdGenerator、
 *       SensitiveValueSanitizer、ToolOperationFingerprint、Clock
 * <p>
 * 首次危险调用：
 * 1. 从 ToolRegistry 获取真实 ToolDefinition
 * 2. 调用 ToolApprovalPolicy
 * 3. 不需要审批则 chain.proceed
 * 4. 需要审批且 invocation.approval 为空：
 *    - 生成 approvalId
 *    - 计算 fingerprint
 *    - 脱敏参数
 *    - 构造 InterruptPayload
 *    - 构造 PENDING PendingApproval
 *    - 抛 AgentInterruptSignal
 *    - 不得调用下游和 Terminal
 *    - 不得返回伪成功 ToolResult
 * <p>
 * 已有 approval 时：
 * - PENDING：严格校验绑定信息，匹配后重新抛出中断
 * - APPROVED：严格校验绑定信息，校验通过后 chain.proceed
 * - REJECTED：严格校验绑定信息，返回 success=false ToolResult
 * - 非法或不匹配：抛 INVALID_APPROVAL_DECISION
 * <p>
 * order = 0，在参数校验之后执行。
 */
public class ToolApprovalInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalInterceptor.class);

    private final ToolRegistry toolRegistry;
    private final ToolApprovalPolicy approvalPolicy;
    private final ApprovalIdGenerator approvalIdGenerator;
    private final SensitiveValueSanitizer sanitizer;
    private final ToolOperationFingerprint fingerprint;
    private final HumanApprovalGateway humanApprovalGateway;
    private final Clock clock;

    public ToolApprovalInterceptor(
            ToolRegistry toolRegistry,
            ToolApprovalPolicy approvalPolicy,
            ApprovalIdGenerator approvalIdGenerator,
            SensitiveValueSanitizer sanitizer,
            ToolOperationFingerprint fingerprint,
            HumanApprovalGateway humanApprovalGateway,
            Clock clock) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy);
        this.approvalIdGenerator = Objects.requireNonNull(approvalIdGenerator);
        this.sanitizer = Objects.requireNonNull(sanitizer);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.humanApprovalGateway = Objects.requireNonNull(humanApprovalGateway);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain) {
        String toolName = invocation.toolCall().name();
        RunContext runContext = invocation.runContext();
        ToolCall toolCall = invocation.toolCall();

        // 查找工具定义
        Optional<com.ksyun.agent.core.tool.AgentTool> agentTool = toolRegistry.find(toolName);
        if (agentTool.isEmpty()) {
            // 工具未注册，交给后续处理（TerminalToolExecutor 会抛 TOOL_NOT_FOUND）
            return chain.proceed(invocation);
        }

        ToolDefinition definition = agentTool.get().definition();

        // 调用审批策略
        ToolApprovalRequirement requirement = approvalPolicy.evaluate(definition, toolCall, runContext);

        // 不需要审批，继续执行
        if (!requirement.required()) {
            return chain.proceed(invocation);
        }

        Optional<PendingApproval> existingApproval = invocation.approval();

        // 需要审批且 approval 为空 → 首次危险调用
        if (existingApproval.isEmpty()) {
            String approvalId = approvalIdGenerator.generate();
            String operationFingerprint = fingerprint.compute(runContext.runId(), toolCall);
            Map<String, Object> safeArguments = sanitizer.sanitize(toolCall.arguments());
            Instant now = clock.instant();

            InterruptPayload payload = new InterruptPayload(
                    approvalId,
                    runContext.runId(),
                    runContext.threadId(),
                    runContext.userId(),
                    "", // agentName 由上层节点补充
                    "", // nodeName 由上层节点补充
                    requirement.displayReason(),
                    OperationType.TOOL,
                    toolName,
                    safeArguments,
                    definition.riskLevel(),
                    now,
                    toolCall.id(),
                    operationFingerprint
            );

            PendingApproval pendingApproval = new PendingApproval(
                    payload,
                    ApprovalStatus.PENDING,
                    null,
                    now,
                    now
            );

            log.info("Tool approval required: toolName={}, approvalId={}, runId={}, userId={}",
                    toolName, approvalId, runContext.runId(), runContext.userId());

            // 先确认 LangChain4j PendingResponse 已注册，再让现有节点保存 Checkpoint 并退出。
            humanApprovalGateway.interrupt(pendingApproval);
            throw new AgentInterruptSignal(pendingApproval);
        }

        // 已有 approval → 根据状态处理
        PendingApproval approval = existingApproval.get();
        return handleExistingApproval(invocation, chain, approval, definition, runContext, toolCall);
    }

    private ToolResult handleExistingApproval(ToolInvocation invocation,
                                                ToolExecutionChain chain,
                                                PendingApproval approval,
                                                ToolDefinition definition,
                                                RunContext runContext,
                                                ToolCall toolCall) {
        String currentFingerprint = fingerprint.compute(runContext.runId(), toolCall);

        // 校验绑定信息
        validateBinding(approval, runContext, toolCall, currentFingerprint);

        switch (approval.status()) {
            case PENDING -> {
                // 重新抛出相同中断
                log.info("Tool approval still pending: toolName={}, approvalId={}, runId={}",
                        toolCall.name(), approval.approvalId(), runContext.runId());
                throw new AgentInterruptSignal(approval);
            }
            case APPROVED -> {
                // 校验通过后继续执行
                log.info("Tool approval granted: toolName={}, approvalId={}, runId={}",
                        toolCall.name(), approval.approvalId(), runContext.runId());
                return chain.proceed(invocation);
            }
            case REJECTED -> {
                // 返回失败 ToolResult
                log.info("Tool approval rejected: toolName={}, approvalId={}, runId={}",
                        toolCall.name(), approval.approvalId(), runContext.runId());
                return ToolResult.failure(
                        AgentErrorCode.APPROVAL_REJECTED.name(),
                        "人工审批已拒绝该工具操作，工具未执行。"
                );
            }
            default -> throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Unexpected approval status: " + approval.status()
            );
        }
    }

    private void validateBinding(PendingApproval approval,
                                  RunContext runContext,
                                  ToolCall toolCall,
                                  String currentFingerprint) {
        InterruptPayload payload = approval.payload();

        // runId 匹配
        if (!payload.runId().equals(runContext.runId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval runId does not match current runId"
            );
        }

        // userId 匹配
        if (!payload.userId().equals(runContext.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval userId does not match current userId"
            );
        }

        // toolCallId 匹配
        if (!payload.toolCallId().equals(toolCall.id())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval toolCallId does not match current toolCallId"
            );
        }

        // toolName 匹配
        if (!payload.operationName().equals(toolCall.name())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval toolName does not match current toolName"
            );
        }

        // fingerprint 必须非空且完全匹配
        if (payload.operationFingerprint() == null || payload.operationFingerprint().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "TOOL approval operationFingerprint must not be empty"
            );
        }
        if (!payload.operationFingerprint().equals(currentFingerprint)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval fingerprint does not match current operation"
            );
        }
    }
}
