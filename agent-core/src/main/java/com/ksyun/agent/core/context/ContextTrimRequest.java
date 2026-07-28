package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * 上下文裁剪请求，不可变。
 * <p>
 * 约束：
 * - messages 为不可变列表，构造时复制
 * - maxMessages 必须 > 0（消息数裁剪时）
 * - maxMessages 表示希望保留的非 System 消息数量
 * - tokenBudget 在 Token 裁剪时必须提供
 * - additionalReservedTokens 用于单次请求额外预留，默认 0，不得为负
 * - 最终可用消息 Token = tokenBudget.availableMessageTokens - additionalReservedTokens
 * - 最终可用消息 Token 必须 > 0
 * - 不得允许调用者直接提交 estimatedTokenCount
 * - 不包含 Spring AI 类型、RunContext 或 UserSession
 * - 不使用无类型 Map 表达限制
 */
public record ContextTrimRequest(
        List<AgentMessage> messages,
        int maxMessages,
        ContextTokenBudget tokenBudget,
        int additionalReservedTokens
) {

    public ContextTrimRequest {
        Objects.requireNonNull(messages, "messages must not be null");
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0, got: " + maxMessages);
        }
        if (additionalReservedTokens < 0) {
            throw new IllegalArgumentException(
                    "additionalReservedTokens must be >= 0, got: " + additionalReservedTokens);
        }
        // Token 裁剪时校验最终可用预算
        if (tokenBudget != null) {
            long effective = (long) tokenBudget.availableMessageTokens() - additionalReservedTokens;
            if (effective <= 0) {
                throw new IllegalArgumentException(
                        "effective message budget must be > 0: availableMessageTokens="
                                + tokenBudget.availableMessageTokens()
                                + " - additionalReservedTokens=" + additionalReservedTokens
                                + " = " + effective);
            }
        }
        messages = List.copyOf(messages);
    }

    /**
     * 创建仅消息数裁剪的请求。
     */
    public static ContextTrimRequest forMessageCount(List<AgentMessage> messages, int maxMessages) {
        return new ContextTrimRequest(messages, maxMessages, null, 0);
    }

    /**
     * 创建包含 Token 预算的裁剪请求。
     *
     * @param messages                消息列表
     * @param maxMessages             最大消息数量
     * @param tokenBudget             Token 预算，不能为 null
     * @param additionalReservedTokens 额外预留 Token
     */
    public static ContextTrimRequest withTokenBudget(
            List<AgentMessage> messages,
            int maxMessages,
            ContextTokenBudget tokenBudget,
            int additionalReservedTokens) {
        Objects.requireNonNull(tokenBudget, "tokenBudget must not be null for token trimming");
        return new ContextTrimRequest(messages, maxMessages, tokenBudget, additionalReservedTokens);
    }

    /**
     * 获取有效消息 Token 预算。
     * <p>
     * 等于 tokenBudget.availableMessageTokens - additionalReservedTokens。
     * 仅在 tokenBudget 不为 null 时有意义。
     *
     * @return 有效消息 Token 预算，tokenBudget 为 null 时返回 0
     */
    public int effectiveMessageBudget() {
        if (tokenBudget == null) {
            return 0;
        }
        return tokenBudget.availableMessageTokens() - additionalReservedTokens;
    }

    /**
     * 判断是否包含 Token 预算。
     */
    public boolean hasTokenBudget() {
        return tokenBudget != null;
    }
}
