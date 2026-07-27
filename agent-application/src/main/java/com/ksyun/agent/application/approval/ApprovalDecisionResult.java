package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.approval.ApprovalStatus;

import java.time.Instant;

/**
 * 审批决定结果，不可变。
 * <p>
 * 约束：
 * - 不得返回 stateData
 * - 不得返回 Session ID
 * - 不得返回原始工具参数
 * - safeArguments 留给后续审批详情接口
 * - 不得返回完整 UserSession 或 RunContext
 * - 不得返回完整 operationFingerprint
 * - 不得返回密码、密钥和权限集合
 */
public record ApprovalDecisionResult(
        String runId,
        String threadId,
        String approvalId,
        ApprovalStatus status,
        String operationName,
        String decidedBy,
        String comment,
        Instant decidedAt,
        long checkpointVersion
) {
}
