package com.ksyun.agent.core.agent;

import com.ksyun.agent.core.run.RunStatus;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行结果。
 *
 * @param agentName Agent 名称
 * @param success   是否成功
 * @param content   结果内容
 * @param evidence  证据列表，不可变
 * @param metadata  元数据，不可变
 * @param errorCode 错误码
 * @param status    运行状态
 */
public record AgentResult(
        String agentName,
        boolean success,
        String content,
        List<String> evidence,
        Map<String, Object> metadata,
        String errorCode,
        RunStatus status
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public AgentResult {
        evidence = evidence == null ? List.of() : Collections.unmodifiableList(evidence);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    /**
     * 兼容两参数构造器，status 由 success 推断。
     */
    public AgentResult(String agentName, boolean success, String content,
                       List<String> evidence, Map<String, Object> metadata, String errorCode) {
        this(agentName, success, content, evidence, metadata, errorCode,
                success ? RunStatus.COMPLETED : RunStatus.FAILED);
    }

    /**
     * 创建成功结果。
     */
    public static AgentResult success(String agentName, String content) {
        return new AgentResult(agentName, true, content, List.of(), Map.of(), null, RunStatus.COMPLETED);
    }

    /**
     * 创建带证据的成功结果。
     */
    public static AgentResult success(String agentName, String content, List<String> evidence) {
        return new AgentResult(agentName, true, content, evidence, Map.of(), null, RunStatus.COMPLETED);
    }

    /**
     * 创建失败结果。
     */
    public static AgentResult failure(String agentName, String errorCode, String content) {
        return new AgentResult(agentName, false, content, List.of(), Map.of(), errorCode, RunStatus.FAILED);
    }

    /**
     * 创建挂起结果。
     * <p>
     * 不得用普通 failure 冒充挂起。
     * 不得包含 Checkpoint、stateData、原始参数、sessionId、roles、permissions、完整 fingerprint。
     *
     * @param agentName     Agent 名称
     * @param approvalId    审批 ID
     * @param operationName 操作名称
     * @param riskLevel     风险等级
     * @param requestedAt   请求时间
     * @return 挂起结果
     */
    public static AgentResult suspended(String agentName,
                                         String approvalId,
                                         String operationName,
                                         String riskLevel,
                                         String requestedAt) {
        return new AgentResult(
                agentName,
                false,
                "运行已暂停，等待人工审批。",
                List.of(),
                Map.of(
                        "approvalId", approvalId,
                        "operationName", operationName,
                        "riskLevel", riskLevel,
                        "requestedAt", requestedAt
                ),
                "APPROVAL_REQUIRED",
                RunStatus.SUSPENDED
        );
    }
}
