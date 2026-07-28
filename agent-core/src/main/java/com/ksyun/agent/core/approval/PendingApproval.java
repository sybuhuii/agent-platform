package com.ksyun.agent.core.approval;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 待审批记录，不可变。
 * <p>
 * 约束：
 * - PENDING 时 decision 必须为空
 * - APPROVED 或 REJECTED 时 decision 必须存在
 * - decision 的 approvalId 和 status 必须与 PendingApproval 一致
 * - 状态转换生成新对象，禁止修改旧对象
 * - 不得直接保存用于展示的原始 ToolCall
 * - Map 和集合必须防御性复制并返回不可变快照（InterruptPayload 已保证）
 *
 * 恢复语义：
 * 本框架采用"节点重跑"恢复——中断发生在危险操作真正执行之前，
 * 恢复后从保存的节点重新执行，不是从 Java 方法中间继续。
 */
public record PendingApproval(
        InterruptPayload payload,
        ApprovalStatus status,
        ApprovalDecision decision,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public PendingApproval {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // PENDING 时 decision 必须为空
        if (status == ApprovalStatus.PENDING && decision != null) {
            throw new IllegalArgumentException(
                    "decision must be null when status is PENDING");
        }
        // APPROVED 或 REJECTED 时 decision 必须存在
        if ((status == ApprovalStatus.APPROVED || status == ApprovalStatus.REJECTED)
                && decision == null) {
            throw new IllegalArgumentException(
                    "decision must not be null when status is " + status);
        }
        // decision 的 approvalId 和 status 必须与 PendingApproval 一致
        if (decision != null) {
            if (!decision.approvalId().equals(payload.approvalId())) {
                throw new IllegalArgumentException(
                        "decision.approvalId must match payload.approvalId");
            }
            if (decision.status() != status) {
                throw new IllegalArgumentException(
                        "decision.status must match PendingApproval status");
            }
        }
    }

    /**
     * 便捷方法：获取 approvalId。
     */
    public String approvalId() {
        return payload.approvalId();
    }

    /**
     * 便捷方法：获取 runId。
     */
    public String runId() {
        return payload.runId();
    }

    /**
     * 便捷方法：获取 threadId。
     */
    public String threadId() {
        return payload.threadId();
    }

    /**
     * 便捷方法：获取 userId。
     */
    public String userId() {
        return payload.userId();
    }
}
