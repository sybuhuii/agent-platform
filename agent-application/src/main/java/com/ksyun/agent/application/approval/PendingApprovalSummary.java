package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.approval.ApprovalStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 待审批摘要，不可变。
 * <p>
 * 约束：
 * - 不返回 stateData
 * - 不返回 sessionId
 * - 不返回 RunContext
 * - 不返回原始工具参数
 * - 不返回完整 operationFingerprint
 * - 不返回密码、密钥、Token 或完整权限集合
 * - safeArguments 只包含 Checkpoint 中已脱敏的值
 * - 查询时不得重新读取原始 ToolCall 参数生成 safeArguments
 * - status 使用现有 ApprovalStatus，不创建第二套状态枚举
 *
 * @param runId         运行 ID
 * @param threadId      线程 ID
 * @param agentName     Agent 名称
 * @param approvalId    审批 ID
 * @param operationType 操作类型
 * @param operationName 操作名称
 * @param riskLevel     风险等级
 * @param reason        审批原因
 * @param requestedAt   请求时间
 * @param status        审批状态
 */
public record PendingApprovalSummary(
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
