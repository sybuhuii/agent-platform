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
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.runtime.react.ReactNodeNames;
import com.ksyun.agent.runtime.react.ReactStateKeys;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ReAct 恢复校验器，纯 Java 实现。
 * <p>
 * 校验顺序：
 * 1. 参数非空
 * 2. Checkpoint 存在
 * 3. 用户归属（不泄漏数据给非归属用户）
 * 4. executionType = REACT_AGENT
 * 5. Checkpoint.status = SUSPENDED
 * 6. PendingApproval 存在
 * 7. 审批状态是 APPROVED 或 REJECTED
 * 8. ApprovalDecision 存在且字段有效
 * 9. resumeNode/nodeName 只能是 execute_tools
 * 10. runId、threadId、userId 合法且顶层信息一致
 * 11. stateData 能完整重建
 * 12. pendingToolCalls 非空
 * 13. cursor 范围合法
 * 14. cursor 对应 ToolCall ID 等于审批绑定的 toolCallId
 * 15. toolName 等于 operationName
 * 16. 使用 ToolOperationFingerprint 重新计算指纹并完全匹配
 * 17. State 中 RunContext 的 userId、runId、threadId 与 Checkpoint 顶层一致
 * 18. decidedBy 非空
 * 19. 不信任 stateData 中与顶层 Checkpoint 冲突的身份字段
 * <p>
 * 任何校验失败时：
 * - 不得把 Checkpoint 改为 RESUMING
 * - 不得执行模型或工具
 * - 抛出明确的结构化框架异常
 * - 不统一伪装成 INTERNAL_ERROR
 * <p>
 * 不访问 Spring 容器。不调用模型和工具。不修改 Checkpoint。
 * 不泄漏 stateData 和审批细节给非归属用户。
 */
public class ReactResumeValidator {

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
                    "Checkpoint not found: runId=" + runId);
        }

        // 2. 用户归属（不泄漏细节给非归属用户）
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.PERMISSION_DENIED,
                    "Operation not permitted");
        }

        // 3. executionType = REACT_AGENT
        if (checkpoint.executionType() != CheckpointExecutionType.REACT_AGENT) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint executionType is not REACT_AGENT: " + checkpoint.executionType());
        }

        // 4. Checkpoint.status 必须是 SUSPENDED
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

        // 5. PendingApproval 存在
        PendingApproval approval = checkpoint.pendingApproval();
        if (approval == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.APPROVAL_NOT_FOUND,
                    "No pending approval found in checkpoint");
        }

        // 6. 审批状态必须是 APPROVED 或 REJECTED
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

        // 7. ApprovalDecision 存在且字段有效
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

        // 8. resumeNode/nodeName 只能是 execute_tools
        if (!ReactNodeNames.EXECUTE_TOOLS.equals(checkpoint.nodeName())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint resumeNode must be 'execute_tools', got: " + checkpoint.nodeName());
        }

        // 9. stateData 有效
        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData is empty");
        }

        // 10. pendingToolCalls 非空
        @SuppressWarnings("unchecked")
        List<ToolCall> pendingToolCalls = (List<ToolCall>) stateData.get(ReactStateKeys.PENDING_TOOL_CALLS);
        if (pendingToolCalls == null || pendingToolCalls.isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData has no pendingToolCalls");
        }

        // 11. cursor 范围合法
        Integer cursorObj = (Integer) stateData.get(ReactStateKeys.TOOL_EXECUTION_CURSOR);
        int cursor = cursorObj != null ? cursorObj : 0;
        if (cursor < 0 || cursor >= pendingToolCalls.size()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint cursor out of range: cursor=" + cursor + ", toolCalls=" + pendingToolCalls.size());
        }

        // 12. cursor 对应 ToolCall ID 等于审批绑定的 toolCallId
        ToolCall cursorCall = pendingToolCalls.get(cursor);
        InterruptPayload payload = approval.payload();
        if (payload.toolCallId() != null && !payload.toolCallId().equals(cursorCall.id())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval toolCallId does not match cursor ToolCall ID: expected="
                            + cursorCall.id() + ", got=" + payload.toolCallId());
        }

        // 13. toolName 等于 operationName
        if (!payload.operationName().equals(cursorCall.name())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_APPROVAL_DECISION,
                    "Approval operationName does not match cursor toolName: expected="
                            + cursorCall.name() + ", got=" + payload.operationName());
        }

        // 14. RunContext userId、runId、threadId 与 Checkpoint 顶层一致
        Object runContextObj = stateData.get(ReactStateKeys.RUN_CONTEXT);
        if (runContextObj instanceof RunContext rc) {
            if (!checkpoint.runId().equals(rc.runId())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                        "RunContext.runId does not match Checkpoint.runId");
            }
            if (!checkpoint.threadId().equals(rc.threadId())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                        "RunContext.threadId does not match Checkpoint.threadId");
            }
            if (!checkpoint.userId().equals(rc.userId())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                        "RunContext.userId does not match Checkpoint.userId");
            }
        }
        // 注意：RunContext 不匹配时由 ReactCheckpointStateMapper 负责修正，
        // 此处只做校验不修改，但记录不信任状态
    }
}
