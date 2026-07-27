package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.approval.ApprovalAction;

import java.util.Objects;

/**
 * 审批决定请求 DTO。
 * <p>
 * 约束：
 * - 不包含 userId、roles、permissions（身份来自已验证 UserSession）
 * - 不包含 decidedAt 和 decidedBy（由服务端生成）
 * - comment 允许为空
 */
public record ApprovalDecideRequest(
        String runId,
        String approvalId,
        ApprovalAction action,
        String comment
) {

    public ApprovalDecideRequest {
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        if (approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        Objects.requireNonNull(action, "action must not be null");
    }
}
