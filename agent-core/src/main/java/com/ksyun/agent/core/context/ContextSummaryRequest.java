package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 上下文摘要请求，不可变。
 * <p>
 * 约束：
 * - sourceMessages 类型为不可变 List<AgentMessage>
 * - 源消息不能为空
 * - 源消息不得包含原始 System 消息
 * - 源消息允许包含最多一条旧 SummaryAgentMessage
 * - existingSummary 使用 Optional<SummaryAgentMessage>
 * - 不得同时在 sourceMessages 和 existingSummary 重复保存同一个摘要对象
 * - maxSummaryTokens 必须 > 0
 * - 不包含 ModelRequest
 * - 不包含模型名称、API Key 和供应商客户端
 * - 不包含 UserSession 或 RunContext
 * - 不使用 Map 替代固定字段
 */
public record ContextSummaryRequest(
        List<AgentMessage> sourceMessages,
        Optional<SummaryAgentMessage> existingSummary,
        int maxSummaryTokens
) {

    public ContextSummaryRequest {
        Objects.requireNonNull(sourceMessages, "sourceMessages must not be null");
        if (sourceMessages.isEmpty()) {
            throw new IllegalArgumentException("sourceMessages must not be empty");
        }

        // 源消息不得包含原始 System 消息
        for (AgentMessage msg : sourceMessages) {
            if (msg instanceof com.ksyun.agent.core.message.SystemAgentMessage) {
                throw new IllegalArgumentException(
                        "sourceMessages must not contain original System messages");
            }
        }

        // 源消息最多一条旧 SummaryAgentMessage
        int summaryCount = 0;
        for (AgentMessage msg : sourceMessages) {
            if (msg instanceof SummaryAgentMessage) {
                summaryCount++;
            }
        }
        if (summaryCount > 1) {
            throw new IllegalArgumentException(
                    "sourceMessages must contain at most one SummaryAgentMessage, found: " + summaryCount);
        }

        // 不得同时在 sourceMessages 和 existingSummary 重复保存同一个摘要对象
        if (existingSummary != null && existingSummary.isPresent()) {
            SummaryAgentMessage existing = existingSummary.get();
            for (AgentMessage msg : sourceMessages) {
                if (msg == existing) {
                    throw new IllegalArgumentException(
                            "Same SummaryAgentMessage must not appear in both sourceMessages and existingSummary");
                }
            }
        }

        if (maxSummaryTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxSummaryTokens must be > 0, got: " + maxSummaryTokens);
        }

        sourceMessages = List.copyOf(sourceMessages);
        existingSummary = existingSummary == null ? Optional.empty() : existingSummary;
    }
}
