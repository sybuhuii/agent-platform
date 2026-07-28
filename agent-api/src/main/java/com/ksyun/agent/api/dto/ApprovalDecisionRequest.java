package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.approval.ApprovalAction;

import java.util.Objects;

/**
 * 审批决定请求 DTO。
 * <p>
 * runId 来自 URL 路径，不在 Body 重复提交。
 * <p>
 * 约束：
 * - 不包含 userId、roles、permissions（身份来自已验证 UserSession）
 * - 不包含 decidedAt 和 decidedBy（由服务端生成）
 * - 不包含 checkpointVersion、operationFingerprint
 * - action 只能为 APPROVE 或 REJECT
 * - comment 允许为空，trim 并限制合理长度
 * - 空 approvalId 和非法 action 返回 400
 * - 不得将未知字符串静默映射为 APPROVE
 */
public record ApprovalDecisionRequest(
        String approvalId,
        ApprovalAction action,
        String comment
) {

    private static final int MAX_COMMENT_LENGTH = 1000;

    public ApprovalDecisionRequest {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        if (approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        Objects.requireNonNull(action, "action must not be null");
        // comment 允许为空，trim 并限制长度
        if (comment != null && comment.trim().length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("comment must not exceed " + MAX_COMMENT_LENGTH + " characters");
        }
    }

    /**
     * 获取 trim 后的 comment。
     */
    public String trimmedComment() {
        return comment == null ? null : comment.trim();
    }
}
