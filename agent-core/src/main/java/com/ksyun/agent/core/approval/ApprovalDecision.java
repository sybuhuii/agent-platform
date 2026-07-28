package com.ksyun.agent.core.approval;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 审批决定，不可变模型。
 * <p>
 * 约束：
 * - status 只能是 APPROVED 或 REJECTED，不得使用 PENDING
 * - approvalId、decidedBy、decidedAt 非空
 * - comment 允许为空，null 规范化为空字符串
 * - 不包含 Session、HTTP、密码、权限集合或模型输出身份
 */
public record ApprovalDecision(
        String approvalId,
        ApprovalStatus status,
        String decidedBy,
        String comment,
        Instant decidedAt
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ApprovalDecision {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        if (approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (status == ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("ApprovalDecision status must not be PENDING");
        }
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        if (decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy must not be blank");
        }
        // comment 允许为空，null 规范化为空字符串
        comment = comment == null ? "" : comment;
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
    }
}
