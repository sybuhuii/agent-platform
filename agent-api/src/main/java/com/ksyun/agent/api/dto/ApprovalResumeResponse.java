package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.run.RunStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * - metadata 只允许白名单字段，不直接透传任意 Map
 * - 保持原 runId 和 threadId
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

    /** metadata 安全白名单字段 */
    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "approvalId", "operationName", "riskLevel", "requestedAt"
    );

    /**
     * 从 AgentResult 和额外信息构造响应，自动过滤 metadata。
     */
    public static ApprovalResumeResponse from(String runId, String threadId,
                                                com.ksyun.agent.core.agent.AgentResult result) {
        String approvalId = "";
        String operationName = "";
        String riskLevel = "";

        // 从 result.metadata() 白名单提取
        Map<String, Object> rawMetadata = result.metadata();
        if (rawMetadata != null) {
            approvalId = safeGetString(rawMetadata, "approvalId");
            operationName = safeGetString(rawMetadata, "operationName");
            riskLevel = safeGetString(rawMetadata, "riskLevel");
        }

        // 构造安全白名单 metadata
        Map<String, Object> safeMetadata = new HashMap<>();
        if (rawMetadata != null) {
            for (String key : ALLOWED_METADATA_KEYS) {
                Object value = rawMetadata.get(key);
                if (value != null) {
                    safeMetadata.put(key, value);
                }
            }
        }

        return new ApprovalResumeResponse(
                runId,
                threadId,
                result.agentName(),
                result.success(),
                result.content(),
                result.errorCode(),
                result.evidence(),
                result.status(),
                approvalId,
                operationName,
                riskLevel,
                Collections.unmodifiableMap(safeMetadata)
        );
    }

    private static String safeGetString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : "";
    }
}
