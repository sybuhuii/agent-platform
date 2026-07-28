package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 审批恢复响应 DTO。
 * <p>
 * 约束：
 * - 恢复完成时返回最终 Agent 结果
 * - 恢复后再次挂起时 status=SUSPENDED，返回新的 approvalId
 * - 拒绝后模型正常解释时可返回 success=true
 * - 不得返回 Session ID
 * - 不得返回 Checkpoint、stateData
 * - 不得返回完整消息历史、ToolTrace、Graph State
 * - 不得返回原始工具参数、Java 异常对象
 * - 不为挂起状态创建第二套状态枚举
 * - metadata 只包含安全白名单字段
 * - 保持原 runId 和 threadId（不传空字符串）
 * - 再次挂起返回新的 approvalId
 */
public record ApprovalResumeResponse(
        String runId,
        String threadId,
        String agentName,
        boolean success,
        String content,
        String errorCode,
        List<String> evidence,
        RunStatus status,
        String approvalId,
        String operationName,
        String riskLevel,
        Map<String, Object> safeMetadata
) {
}
