package com.ksyun.agent.core.approval;

import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 中断时展示给审批人的信息，不可变。
 * <p>
 * 约束：
 * - safeArguments 只保存脱敏和长度限制后的参数
 * - 不得保存 sessionId、密码、token、credentialHash、API Key
 * - 不得保存 Spring AI、LangGraph4j、Servlet、异常或 Bean
 * - reason 是安全展示说明，不是模型思维链
 * - 不得把原始 ToolCall arguments 放入可展示 payload
 * - operationFingerprint 不得发送给 LLM
 */
public record InterruptPayload(
        String approvalId,
        String runId,
        String threadId,
        String userId,
        String agentName,
        String nodeName,
        String reason,
        OperationType operationType,
        String operationName,
        Map<String, Object> safeArguments,
        ToolRiskLevel riskLevel,
        Instant requestedAt,
        String toolCallId,
        String operationFingerprint
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InterruptPayload {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        if (approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId must not be blank");
        }
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(threadId, "threadId must not be null");
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        Objects.requireNonNull(agentName, "agentName must not be null");
        // agentName 允许空字符串，由上层节点（ReactCheckpointService）补充真实值
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        // nodeName 允许空字符串，由上层节点（ReactCheckpointService）补充真实值
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        Objects.requireNonNull(operationType, "operationType must not be null");
        Objects.requireNonNull(operationName, "operationName must not be null");
        if (operationName.isBlank()) {
            throw new IllegalArgumentException("operationName must not be blank");
        }
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");

        // safeArguments 防御性处理
        if (safeArguments == null) {
            safeArguments = Map.of();
        } else {
            safeArguments = Collections.unmodifiableMap(safeArguments);
        }

        // toolCallId 可为空（未来节点级中断可能没有）
        // operationFingerprint 可为空（由运行时生成）
    }
}
