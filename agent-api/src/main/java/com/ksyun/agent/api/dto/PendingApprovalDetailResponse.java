package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.approval.ApprovalStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 待审批详情响应 DTO。
 * <p>
 * 约束：
 * - 不返回 stateData
 * - 不返回 sessionId
 * - 不返回原始工具参数
 * - 不返回完整 operationFingerprint
 * - 不返回密码、密钥和权限集合
 * - safeArguments 已脱敏，不可变快照
 */
public record PendingApprovalDetailResponse(
        String runId,
        String threadId,
        String agentName,
        String approvalId,
        String operationType,
        String operationName,
        String riskLevel,
        String reason,
        Instant requestedAt,
        ApprovalStatus status,
        String nodeName,
        Map<String, Object> safeArguments,
        Instant createdAt,
        Instant updatedAt,
        long checkpointVersion
) {
}
