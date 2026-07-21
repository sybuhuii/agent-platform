package com.ksyun.agent.core.approval;

import com.ksyun.agent.core.tool.ToolCall;

import java.time.Instant;

/**
 * 待审批记录。
 * <p>
 * 当前只定义数据模型，不实现 interrupt/resume。
 *
 * @param approvalId 审批 ID
 * @param runId      运行 ID
 * @param toolCall   触发审批的工具调用
 * @param reason     审批原因
 * @param status     审批状态
 * @param createdAt  创建时间
 */
public record PendingApproval(
        String approvalId,
        String runId,
        ToolCall toolCall,
        String reason,
        ApprovalStatus status,
        Instant createdAt
) {
}
