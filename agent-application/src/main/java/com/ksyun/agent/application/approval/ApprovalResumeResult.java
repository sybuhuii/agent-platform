package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.run.RunStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 审批恢复结果，不可变。
 * <p>
 * 统一覆盖独立 React Agent 恢复和嵌套 Supervisor 恢复。
 * 不再直接返回 ReactResumeResult，而是在 Application 层转换为通用结果。
 * <p>
 * 约束：
 * - 不得返回 Session ID、stateData、Checkpoint
 * - 不得返回完整消息历史、ToolTrace、Graph State
 * - 不得返回原始工具参数、Java 异常对象
 * - metadata 只包含安全白名单字段
 * - 再次挂起返回新的 approvalId
 * - 保持原 runId 和 threadId
 * - parentRunId 非空时表示嵌套 Supervisor 恢复
 */
public record ApprovalResumeResult(
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
        Map<String, Object> safeMetadata,
        String parentRunId
) {

    private static final java.util.Set<String> ALLOWED_METADATA_KEYS = java.util.Set.of(
            "approvalId", "approvalRunId", "operationName", "riskLevel", "requestedAt",
            "parentRunId", "parentThreadId", "childRunId", "childThreadId",
            "childTaskId", "dispatchBatchId", "dispatchIndex"
    );

    /**
     * 从独立 React Agent 恢复结果构造。
     */
    public static ApprovalResumeResult fromReactResult(
            com.ksyun.agent.runtime.react.ReactResumeResult reactResult) {
        Objects.requireNonNull(reactResult, "reactResult must not be null");

        Map<String, Object> safeMetadata = filterSafeMetadata(reactResult.safeMetadata());

        return new ApprovalResumeResult(
                reactResult.runId(),
                reactResult.threadId(),
                reactResult.agentName(),
                reactResult.success(),
                reactResult.content(),
                reactResult.errorCode(),
                reactResult.evidence(),
                reactResult.status(),
                reactResult.approvalId(),
                reactResult.operationName(),
                reactResult.riskLevel(),
                safeMetadata,
                null  // 独立 React 无 parentRunId
        );
    }

    /**
     * 从 Supervisor 恢复结果构造。
     */
    public static ApprovalResumeResult fromSupervisorResult(
            com.ksyun.agent.core.agent.AgentResult supervisorResult,
            String parentRunId,
            String parentThreadId) {
        Objects.requireNonNull(supervisorResult, "supervisorResult must not be null");

        Map<String, Object> safeMetadata = filterSafeMetadata(supervisorResult.metadata());

        String approvalId = safeGetString(supervisorResult.metadata(), "approvalId");
        String operationName = safeGetString(supervisorResult.metadata(), "operationName");
        String riskLevel = safeGetString(supervisorResult.metadata(), "riskLevel");

        return new ApprovalResumeResult(
                parentRunId,
                parentThreadId,
                supervisorResult.agentName(),
                supervisorResult.success(),
                supervisorResult.content(),
                supervisorResult.errorCode(),
                supervisorResult.evidence(),
                supervisorResult.status(),
                approvalId,
                operationName,
                riskLevel,
                safeMetadata,
                parentRunId  // Supervisor 恢复时 parentRunId == runId
        );
    }

    private static Map<String, Object> filterSafeMetadata(Map<String, Object> rawMetadata) {
        Map<String, Object> safe = new java.util.HashMap<>();
        if (rawMetadata != null) {
            for (String key : ALLOWED_METADATA_KEYS) {
                Object value = rawMetadata.get(key);
                if (value != null) {
                    safe.put(key, value);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(safe);
    }

    private static String safeGetString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
