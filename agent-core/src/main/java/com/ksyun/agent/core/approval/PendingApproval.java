package com.ksyun.agent.core.approval;

import com.ksyun.agent.core.tool.ToolCall;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 待审批记录，不可变。
 * <p>
 * 当工具执行需要人工审批时，创建此记录并保存到 Checkpoint。
 * <p>
 * 约束：
 * - status 为 PENDING 时不得修改为 APPROVED/REJECTED（本批不实现 approve/reject 流程）
 * - approvalId、runId 不得为空
 * - toolCall 不得为 null
 * - safeArguments 不得包含完整原始参数，仅保留参数名列表用于展示
 * - reason 和 interruptReason 必须一致，不得矛盾
 * - 不得包含密码、credentialHash、sessionId 或 HTTP 对象
 * - 审批结果未持久化前不得继续执行工具
 *
 * @param approvalId      审批 ID，全局唯一
 * @param runId           运行 ID
 * @param threadId        线程 ID
 * @param toolCall        触发审批的工具调用
 * @param interruptReason 中断原因枚举
 * @param reason          中断原因人类可读描述
 * @param safeArguments   脱敏后的参数（只包含参数名列表，不包含值）
 * @param status          审批状态
 * @param createdAt       创建时间
 */
public record PendingApproval(
        String approvalId,
        String runId,
        String threadId,
        ToolCall toolCall,
        InterruptReason interruptReason,
        String reason,
        Map<String, Object> safeArguments,
        ApprovalStatus status,
        Instant createdAt
) {

    public PendingApproval {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        if (approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(threadId, "threadId must not be null");
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(interruptReason, "interruptReason must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        // safeArguments 防御性处理
        if (safeArguments == null) {
            safeArguments = Map.of();
        } else {
            safeArguments = Collections.unmodifiableMap(safeArguments);
        }
    }
}
