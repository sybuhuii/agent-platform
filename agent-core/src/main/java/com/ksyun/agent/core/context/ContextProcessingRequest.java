package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * 统一上下文处理请求，不可变。
 * <p>
 * 约束：
 * - messages 不可变
 * - 至少启用一种裁剪策略
 * - maxMessages 仅在消息数裁剪启用时必须 > 0
 * - tokenBudget 仅在 Token 裁剪启用时必须存在
 * - summaryOptions 为摘要处理所需配置
 * - 不包含模型客户端、Session 和权限
 * - 不得允许调用者提交 Token 统计结果
 * - 提供清晰工厂方法，避免构造参数顺序错误
 * - 不使用 Builder 引入大量可选 null 字段
 */
public record ContextProcessingRequest(
        List<AgentMessage> messages,
        int maxMessages,
        ContextTokenBudget tokenBudget,
        int additionalReservedTokens,
        boolean messageCountTrimmingEnabled,
        boolean tokenTrimmingEnabled,
        ContextSummaryOptions summaryOptions
) {

    public ContextProcessingRequest {
        Objects.requireNonNull(messages, "messages must not be null");

        if (!messageCountTrimmingEnabled && !tokenTrimmingEnabled) {
            throw new IllegalArgumentException(
                    "At least one trimming strategy must be enabled");
        }

        if (messageCountTrimmingEnabled && maxMessages <= 0) {
            throw new IllegalArgumentException(
                    "maxMessages must be > 0 when message count trimming is enabled, got: " + maxMessages);
        }

        if (tokenTrimmingEnabled) {
            Objects.requireNonNull(tokenBudget,
                    "tokenBudget must not be null when token trimming is enabled");
            if (additionalReservedTokens < 0) {
                throw new IllegalArgumentException(
                        "additionalReservedTokens must be >= 0, got: " + additionalReservedTokens);
            }
            long effective = (long) tokenBudget.availableMessageTokens() - additionalReservedTokens;
            if (effective <= 0) {
                throw new IllegalArgumentException(
                        "effective message budget must be > 0 when token trimming is enabled");
            }
        }

        // summaryOptions 默认值
        summaryOptions = summaryOptions == null ? ContextSummaryOptions.disabled() : summaryOptions;

        messages = List.copyOf(messages);
    }

    /**
     * 创建仅消息数裁剪的请求。
     */
    public static ContextProcessingRequest messageCountOnly(List<AgentMessage> messages, int maxMessages) {
        return new ContextProcessingRequest(messages, maxMessages, null, 0, true, false, null);
    }

    /**
     * 创建仅 Token 裁剪的请求。
     */
    public static ContextProcessingRequest tokenOnly(
            List<AgentMessage> messages,
            ContextTokenBudget tokenBudget,
            int additionalReservedTokens) {
        return new ContextProcessingRequest(messages, Integer.MAX_VALUE, tokenBudget,
                additionalReservedTokens, false, true, null);
    }

    /**
     * 创建双重裁剪的请求（消息数 + Token）。
     */
    public static ContextProcessingRequest both(
            List<AgentMessage> messages,
            int maxMessages,
            ContextTokenBudget tokenBudget,
            int additionalReservedTokens) {
        return new ContextProcessingRequest(messages, maxMessages, tokenBudget,
                additionalReservedTokens, true, true, null);
    }

    /**
     * 创建包含摘要选项的请求。
     */
    public static ContextProcessingRequest withSummary(
            List<AgentMessage> messages,
            int maxMessages,
            ContextTokenBudget tokenBudget,
            int additionalReservedTokens,
            boolean messageCountTrimmingEnabled,
            boolean tokenTrimmingEnabled,
            ContextSummaryOptions summaryOptions) {
        return new ContextProcessingRequest(messages, maxMessages, tokenBudget,
                additionalReservedTokens, messageCountTrimmingEnabled, tokenTrimmingEnabled,
                summaryOptions);
    }

    /**
     * 获取有效消息 Token 预算。
     */
    public int effectiveMessageBudget() {
        if (tokenBudget == null) {
            return 0;
        }
        return tokenBudget.availableMessageTokens() - additionalReservedTokens;
    }

    /**
     * 判断摘要是否启用。
     */
    public boolean summaryEnabled() {
        return summaryOptions != null && summaryOptions.summaryEnabled();
    }
}
