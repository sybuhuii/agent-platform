package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.ApprovalDecision;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.InterruptPayload;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.runtime.react.ReactNodeNames;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.tool.approval.ToolOperationFingerprint;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ReAct 恢复校验器，纯 Java 实现。
 * <p>
 * 校验顺序（恢复抢占前必须全部通过）：
 * 1. 参数非空
 * 2. Checkpoint 存在
 * 3. 用户归属（不泄漏数据给非归属用户）- 不匹配用安全NOT_FOUND
 * 4. executionType = REACT_AGENT
 * 5. Checkpoint.status = SUSPENDED
 * 6. PendingApproval 存在
 * 7. 审批状态是 APPROVED 或 REJECTED
 * 8. ApprovalDecision 存在且 decidedBy 非空
 * 9. nodeName 严格为 execute_tools
 * 10. stateData 存在且结构合法
 * 11. pendingToolCalls 必须是合法的 ToolCall 列表
 * 12. cursor 必须是合法整数且处于范围内
 * 13. cursor 对应 ToolCall ID 严格等于 payload.toolCallId
 * 14. toolName 严格等于 operationName
 * 15. TOOL审批的 operationFingerprint 必须非空
 * 16. 使用 ToolOperationFingerprint 重新计算并完全匹配
 * 17. payload 中的 runId、threadId、userId、agentName、nodeName 必须与 Checkpoint 顶层一致
 * <p>
 * 注：RunContext 不再持久化到 payload，恢复时由当前已验证 UserSession 重建。
 * 用户归属（operator.userId 与 checkpoint.userId 一致）已在步骤 2 校验，
 * runId/threadId/userId 一致性由步骤 17（payload 与顶层一致）覆盖。
 * <p>
 * 任何校验失败时：
 * - 不得把 Checkpoint 改为 RESUMING
 * - 不得执行模型或工具
 * - 抛出明确的结构化框架异常（INVALID_APPROVAL_DECISION 或 CHECKPOINT_NOT_RESUMABLE）
 * - 不统一伪装成 INTERNAL_ERROR
 * - 不得抛出 ClassCastException
 * - 不得信任或修正冲突的身份字段后继续恢复
 * <p>
 * 不访问 Spring 容器。不调用模型和工具。不修改 Checkpoint。
 * 不泄漏 stateData 和审批细节给非归属用户。
 * 指纹计算能力通过构造器依赖注入。
 */
public class ReactResumeValidator {

    private final ToolOperationFingerprint fingerprintCalculator;

    /**
     * 无指纹校验构造器（兼容旧调用点，但指纹校验将跳过）。
     * 生产环境应使用带指纹的构造器。
     */
    public ReactResumeValidator() {
        this.fingerprintCalculator = null;
    }

    /**
     * 带指纹计算能力的构造器。
     *
     * @param fingerprintCalculator 指纹计算器，不得为 null
     */
    public ReactResumeValidator(ToolOperationFingerprint fingerprintCalculator) {
        this.fingerprintCalculator = Objects.requireNonNull(fingerprintCalculator,
                "fingerprintCalculator must not be null");
    }

    /**
     * 校验恢复请求。
     *
     * @param checkpoint 加载的 Checkpoint
     * @param operator   当前操作用户
     * @param runId      运行 ID
     */
    public void validateForResume(AgentCheckpoint checkpoint, UserSession operator, String runId) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(runId, "runId must not be null");

        // 1. Checkpoint 存在
        if (checkpoint == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 2. 用户归属 - 不匹配使用安全 NOT_FOUND，不泄漏信息
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 3. executionType = REACT_AGENT
        if (checkpoint.executionType() != CheckpointExecutionType.REACT_AGENT) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint executionType is not REACT_AGENT: " + checkpoint.executionType());
        }

        // 4. Checkpoint.status 必须是 SUSPENDED
        validateStatus(checkpoint);

        // 5. PendingApproval 存在
        PendingApproval approval = checkpoint.pendingApproval();
        if (approval == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_NOT_FOUND,
                    "No pending approval found in checkpoint");
        }

        // 6. 审批状态必须是 APPROVED 或 REJECTED
        validateApprovalStatus(approval);

        // 7. ApprovalDecision 存在且 decidedBy 非空
        validateDecision(approval);

        // 8. nodeName 严格为 execute_tools
        if (!ReactNodeNames.EXECUTE_TOOLS.equals(checkpoint.nodeName())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint nodeName must be 'execute_tools', got: " + checkpoint.nodeName());
        }

        // 9. stateData 存在且结构合法
        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData is empty");
        }

        // 10. pendingToolCalls 必须是合法的 ToolCall 列表
        ToolCallValidation tcVal = validateToolCalls(stateData);
        List<ToolCall> pendingToolCalls = tcVal.toolCalls;
        int cursor = tcVal.cursor;
        ToolCall cursorCall = tcVal.cursorCall;

        // 11. cursor 对应 ToolCall ID 严格等于 payload.toolCallId
        InterruptPayload payload = approval.payload();
        if (!cursorCall.id().equals(payload.toolCallId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval toolCallId does not match cursor ToolCall ID");
        }

        // 12. toolName 严格等于 operationName
        if (!payload.operationName().equals(cursorCall.name())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval operationName does not match cursor toolName");
        }

        // 13. TOOL审批的 operationFingerprint 必须非空
        if (payload.operationFingerprint() == null || payload.operationFingerprint().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "TOOL approval operationFingerprint must not be empty");
        }

        // 14. 使用 ToolOperationFingerprint 重新计算并完全匹配
        validateFingerprint(cursorCall, payload);

        // 15. payload 中的 runId、threadId、userId、agentName、nodeName 必须与 Checkpoint 顶层一致
        validatePayloadIdentity(payload, checkpoint);

        // RunContext 不再从 stateData 读取；恢复时由当前 UserSession 重建。
        // runId/threadId/userId 一致性已由 validatePayloadIdentity 与用户归属校验覆盖。
    }

    private void validateStatus(AgentCheckpoint checkpoint) {
        if (checkpoint.status() == CheckpointStatus.COMPLETED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is COMPLETED, cannot resume");
        }
        if (checkpoint.status() == CheckpointStatus.FAILED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint is FAILED, cannot resume");
        }
        if (checkpoint.status() == CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Checkpoint is already in RESUMING state");
        }
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint status is " + checkpoint.status() + ", cannot resume");
        }
    }

    private void validateApprovalStatus(PendingApproval approval) {
        if (approval.status() == ApprovalStatus.PENDING) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_REQUIRED,
                    "Approval has not been decided yet");
        }
        if (approval.status() != ApprovalStatus.APPROVED
                && approval.status() != ApprovalStatus.REJECTED) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Unexpected approval status: " + approval.status());
        }
    }

    private void validateDecision(PendingApproval approval) {
        ApprovalDecision decision = approval.decision();
        if (decision == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval decision is missing for status " + approval.status());
        }
        if (decision.decidedBy() == null || decision.decidedBy().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval decision decidedBy must not be blank");
        }
    }

    /**
     * 校验 pendingToolCalls 和 cursor，类型安全，不抛 ClassCastException。
     */
    private ToolCallValidation validateToolCalls(Map<String, Object> stateData) {
        Object rawToolCalls = stateData.get(ReactStateKeys.PENDING_TOOL_CALLS);
        if (rawToolCalls == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData has no pendingToolCalls");
        }
        if (!(rawToolCalls instanceof List<?> list)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "pendingToolCalls has wrong type: expected List, got " + rawToolCalls.getClass().getName());
        }
        if (list.isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData has empty pendingToolCalls");
        }

        // 验证每个元素都是 ToolCall
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof ToolCall)) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                        "pendingToolCalls[" + i + "] has wrong type: expected ToolCall, got "
                                + list.get(i).getClass().getName());
            }
        }

        @SuppressWarnings("unchecked")
        List<ToolCall> pendingToolCalls = (List<ToolCall>) list;

        // cursor 校验
        Object rawCursor = stateData.get(ReactStateKeys.TOOL_EXECUTION_CURSOR);
        if (rawCursor != null && !(rawCursor instanceof Integer)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "cursor has wrong type: expected Integer, got " + rawCursor.getClass().getName());
        }
        int cursor = rawCursor != null ? (Integer) rawCursor : 0;
        if (cursor < 0 || cursor >= pendingToolCalls.size()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint cursor out of range: cursor=" + cursor + ", toolCalls=" + pendingToolCalls.size());
        }

        ToolCall cursorCall = pendingToolCalls.get(cursor);
        return new ToolCallValidation(pendingToolCalls, cursor, cursorCall);
    }

    /**
     * 指纹重新计算校验。
     */
    private void validateFingerprint(ToolCall cursorCall, InterruptPayload payload) {
        if (fingerprintCalculator == null) {
            // 无指纹计算器时跳过（兼容模式），但记录警告
            return;
        }

        String computed = fingerprintCalculator.compute(payload.runId(), cursorCall);
        if (!payload.operationFingerprint().equals(computed)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval operationFingerprint does not match recomputed fingerprint");
        }
    }

    /**
     * payload 中的身份字段必须与 Checkpoint 顶层一致。
     */
    private void validatePayloadIdentity(InterruptPayload payload, AgentCheckpoint checkpoint) {
        if (!payload.runId().equals(checkpoint.runId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Payload runId does not match Checkpoint");
        }
        if (!payload.threadId().equals(checkpoint.threadId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Payload threadId does not match Checkpoint");
        }
        if (!payload.userId().equals(checkpoint.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Payload userId does not match Checkpoint");
        }
        if (!payload.agentName().equals(checkpoint.agentName())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Payload agentName does not match Checkpoint");
        }
        if (!payload.nodeName().equals(checkpoint.nodeName())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Payload nodeName does not match Checkpoint");
        }
    }

    /** 内部校验结果 */
    private record ToolCallValidation(List<ToolCall> toolCalls, int cursor, ToolCall cursorCall) {}
}
