package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.time.Instant;
import java.util.Map;

/**
 * 待审批记录摘要响应 DTO。
 * <p>
 * 约束：
 * - 不得返回 stateData
 * - 不得返回 Session ID
 * - 不得返回原始工具参数
 * - safeArguments 已脱敏
 * - 不得返回完整 operationFingerprint
 * - 不得返回密码、密钥和权限集合
 */
public record PendingApprovalSummaryResponse(
        String runId,
        String threadId,
        String approvalId,
        String agentName,
        String operationName,
        ToolRiskLevel riskLevel,
        ApprovalStatus status,
        Map<String, Object> safeArguments,
        Instant requestedAt,
        String reason
) {
}
