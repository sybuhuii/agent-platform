package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.run.RunStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ReAct 恢复执行结果，不可变。
 * <p>
 * 包含恢复执行后的关键业务信息：
 * - runId 和 threadId 来自原 Checkpoint
 * - agentName 来自 AgentResult
 * - 不得返回 Session ID、stateData、ToolTrace 或完整消息
 * - 再次挂起返回新的 approvalId
 * - metadata 只包含安全白名单字段
 * <p>
 * 约束：
 * - 不得让 Controller 自行查询 Checkpoint 补 threadId
 * - 不得传空字符串或生成新 threadId
 * - 使用原 Checkpoint 中的 runId 和 threadId
 */
public record ReactResumeResult(
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
    private static final java.util.Set<String> ALLOWED_METADATA_KEYS = java.util.Set.of(
            "approvalId", "operationName", "riskLevel", "requestedAt"
    );

    /**
     * 从 AgentResult 和 Checkpoint 信息构造恢复结果。
     *
     * @param runId     原 Checkpoint 的 runId
     * @param threadId  原 Checkpoint 的 threadId
     * @param result    Agent 执行结果
     * @return 恢复结果
     */
    public static ReactResumeResult from(String runId, String threadId, AgentResult result) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(result, "result must not be null");

        String approvalId = "";
        String operationName = "";
        String riskLevel = "";

        Map<String, Object> rawMetadata = result.metadata();
        if (rawMetadata != null) {
            approvalId = safeGetString(rawMetadata, "approvalId");
            operationName = safeGetString(rawMetadata, "operationName");
            riskLevel = safeGetString(rawMetadata, "riskLevel");
        }

        // 构造安全白名单 metadata
        Map<String, Object> safeMetadata = new java.util.HashMap<>();
        if (rawMetadata != null) {
            for (String key : ALLOWED_METADATA_KEYS) {
                Object value = rawMetadata.get(key);
                if (value != null) {
                    safeMetadata.put(key, value);
                }
            }
        }

        return new ReactResumeResult(
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
                java.util.Collections.unmodifiableMap(safeMetadata)
        );
    }

    private static String safeGetString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : "";
    }
}
