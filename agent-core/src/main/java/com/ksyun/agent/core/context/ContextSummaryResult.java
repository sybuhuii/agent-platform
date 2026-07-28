package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.SummaryAgentMessage;

import java.util.Objects;

/**
 * 上下文摘要结果，不可变。
 * <p>
 * 约束：
 * - summaryMessage 必须为 SummaryAgentMessage
 * - sourceMessageCount 必须 > 0
 * - sourceTokenCount 必须 > 0
 * - summaryTokenCount 必须 > 0
 * - summaryTokenCount 不得超过请求的 maxSummaryTokens
 * - existingSummaryReplaced 准确反映是否合并过旧摘要
 * - 不返回模型原始响应
 * - 不返回摘要 Prompt
 * - 不返回完整源消息副本
 * - 不返回 ToolCall
 * - 不包含 Spring AI 类型
 */
public record ContextSummaryResult(
        SummaryAgentMessage summaryMessage,
        int sourceMessageCount,
        int sourceTokenCount,
        int summaryTokenCount,
        boolean existingSummaryReplaced
) {

    public ContextSummaryResult {
        Objects.requireNonNull(summaryMessage, "summaryMessage must not be null");
        if (sourceMessageCount <= 0) {
            throw new IllegalArgumentException(
                    "sourceMessageCount must be > 0, got: " + sourceMessageCount);
        }
        if (sourceTokenCount <= 0) {
            throw new IllegalArgumentException(
                    "sourceTokenCount must be > 0, got: " + sourceTokenCount);
        }
        if (summaryTokenCount <= 0) {
            throw new IllegalArgumentException(
                    "summaryTokenCount must be > 0, got: " + summaryTokenCount);
        }
    }

    /**
     * 校验摘要 Token 是否不超过请求的 maxSummaryTokens。
     *
     * @param maxSummaryTokens 请求中指定的最大摘要 Token
     * @return true 如果摘要 Token 在预算内
     */
    public boolean withinSummaryTokenBudget(int maxSummaryTokens) {
        return summaryTokenCount <= maxSummaryTokens;
    }
}
