package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.approval.ApprovalStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 待审批详情，不可变。
 * <p>
 * 约束：
 * - 包含 Summary 全部字段
 * - safeArguments 只包含 Checkpoint 中已脱敏的值，不可变快照
 * - 不返回 stateData
 * - 不返回 sessionId
 * - 不返回 RunContext
 * - 不返回原始工具参数
 * - 不返回完整 operationFingerprint
 * - 不返回密码、密钥、Token 或完整权限集合
 * - createdAt、updatedAt 来自 Checkpoint
 * - checkpointVersion 来自 Checkpoint.version()
 * - status 使用现有 ApprovalStatus
 *
 * @param runId            运行 ID
 * @param threadId         线程 ID
 * @param agentName        Agent 名称
 * @param approvalId       审批 ID
 * @param operationType    操作类型
 * @param operationName    操作名称
 * @param riskLevel        风险等级
 * @param reason           审批原因
 * @param requestedAt      请求时间
 * @param status           审批状态
 * @param nodeName         恢复节点名
 * @param safeArguments    已脱敏的参数（不可变快照）
 * @param createdAt        Checkpoint 创建时间
 * @param updatedAt        Checkpoint 更新时间
 * @param checkpointVersion Checkpoint 版本号
 */
public record PendingApprovalDetail(
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
