package com.ksyun.agent.core.agent;

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
 */
public record AgentResult(
        String agentName,
        boolean success,
        String content,
        List<String> evidence,
        Map<String, Object> metadata,
        String errorCode
) {

    public AgentResult {
        evidence = evidence == null ? List.of() : Collections.unmodifiableList(evidence);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }

    /**
     * 创建成功结果。
     */
    public static AgentResult success(String agentName, String content) {
        return new AgentResult(agentName, true, content, List.of(), Map.of(), null);
    }

    /**
     * 创建带证据的成功结果。
     */
    public static AgentResult success(String agentName, String content, List<String> evidence) {
        return new AgentResult(agentName, true, content, evidence, Map.of(), null);
    }

    /**
     * 创建失败结果。
     */
    public static AgentResult failure(String agentName, String errorCode, String content) {
        return new AgentResult(agentName, false, content, List.of(), Map.of(), errorCode);
    }
}
