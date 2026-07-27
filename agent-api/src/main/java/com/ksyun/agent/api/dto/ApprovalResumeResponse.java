package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.List;
import java.util.Map;

/**
 * 审批恢复响应 DTO。
 * <p>
 * 约束：
 * - 恢复完成时返回最终 Agent 结果
 * - 恢复后再次挂起时 status=SUSPENDED，返回新 approvalId
 * - 拒绝后模型正常解释时可返回 success=true
 * - 不得返回 Session ID、stateData、完整消息历史、ToolTrace、内部 Graph State、原始工具参数、Java 异常对象
 * - 不得为挂起结果创建第二套状态枚举
 */
public record ApprovalResumeResponse(
        String runId,
        String threadId,
        String agentName,
        boolean success,
        String content,
        String errorCode,
        List<String> evidence,
        Map<String, Object> metadata,
        RunStatus status,
        String approvalId,
        String operationName,
        ToolRiskLevel riskLevel
) {
}
