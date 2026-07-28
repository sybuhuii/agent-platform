package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.approval.ApprovalStatus;

import java.time.Instant;

/**
 * 待审批记录摘要响应 DTO。
 * <p>
 * 约束：
 * - 不得返回 stateData
 * - 不得返回 Session ID
 * - 不得返回原始工具参数
 * - 不返回 safeArguments（只用于详情接口）
 * - 不得返回完整 operationFingerprint
 * - 不得返回密码、密钥和权限集合
 * - status 使用现有 ApprovalStatus，不创建第二套状态枚举
 */
public record PendingApprovalSummaryResponse(
        String runId,
        String threadId,
        String agentName,
        String approvalId,
        String operationType,
        String operationName,
        String riskLevel,
        String reason,
        Instant requestedAt,
        ApprovalStatus status
) {
}
