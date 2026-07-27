package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.approval.ApprovalAction;

import java.util.Objects;

/**
 * 审批决定命令，不可变。
 * <p>
 * 约束：
 * - 不包含 userId、roles、permissions（当前用户身份由已验证 UserSession 提供）
 * - comment 允许为空，有合理长度上限
 * - 不得包含 HTTP 或 Servlet 类型
 * - 不得包含完整 Checkpoint
 * - 不得允许客户端提交 decidedAt 和 decidedBy
 */
public record ApprovalDecisionCommand(
        String runId,
        String approvalId,
        ApprovalAction action,
        String comment
) {

    private static final int MAX_COMMENT_LENGTH = 1000;

    public ApprovalDecisionCommand {
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        if (approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        Objects.requireNonNull(action, "action must not be null");
        // comment 允许为空，截断过长内容
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("comment must not exceed " + MAX_COMMENT_LENGTH + " characters");
        }
    }
}
