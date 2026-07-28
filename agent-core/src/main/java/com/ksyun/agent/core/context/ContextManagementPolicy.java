package com.ksyun.agent.core.context;

import java.util.Objects;

/**
 * 上下文管理策略，不可变值对象。
 * <p>
 * 约束：
 * - trimStrategy 非 null
 * - maxMessages 只在 MAX_MESSAGES 策略下有效，必须 > 0
 * - systemPromptAlwaysPreserved 默认 true
 * - latestUserInputPreserved 默认 true
 * - atomicGroupOvershoot 默认 true（允许因原子组完整性超限）
 * - atomicGroupOvershoot=true 时，裁剪到 atomicGroup 边界可能导致消息数略超 maxMessages
 * - atomicGroupOvershoot=false 时，裁剪严格不超过 maxMessages，可能拆散 atomicGroup
 * - 不依赖 Spring、LangGraph4j、模型 API 或 Token 库
 * - 不持有 Session、ModelClient、Registry 或 Gateway
 */
public record ContextManagementPolicy(
        TrimStrategy trimStrategy,
        int maxMessages,
        boolean systemPromptAlwaysPreserved,
        boolean latestUserInputPreserved,
        boolean atomicGroupOvershoot
) {

    /** 默认策略：MAX_MESSAGES=20，System保留，最新用户输入保留，允许overshoot */
    public static final ContextManagementPolicy DEFAULT = new ContextManagementPolicy(
            TrimStrategy.MAX_MESSAGES, 20, true, true, true
    );

    public ContextManagementPolicy {
        Objects.requireNonNull(trimStrategy, "trimStrategy must not be null");

        if (trimStrategy == TrimStrategy.MAX_MESSAGES) {
            if (maxMessages <= 0) {
                throw new IllegalArgumentException("maxMessages must be > 0 for MAX_MESSAGES strategy");
            }
        }
    }

    /**
     * 创建 MAX_MESSAGES 策略。
     */
    public static ContextManagementPolicy maxMessages(int maxMessages) {
        return new ContextManagementPolicy(TrimStrategy.MAX_MESSAGES, maxMessages, true, true, true);
    }

    /**
     * 创建 MAX_MESSAGES 策略，自定义所有参数。
     */
    public static ContextManagementPolicy maxMessages(int maxMessages,
                                                        boolean systemPromptAlwaysPreserved,
                                                        boolean latestUserInputPreserved,
                                                        boolean atomicGroupOvershoot) {
        return new ContextManagementPolicy(TrimStrategy.MAX_MESSAGES, maxMessages,
                systemPromptAlwaysPreserved, latestUserInputPreserved, atomicGroupOvershoot);
    }

    /**
     * 转换为 ContextTrimRequest。
     *
     * @param messages 消息列表
     * @return 裁剪请求
     */
    public ContextTrimRequest toTrimRequest(java.util.List<com.ksyun.agent.core.message.AgentMessage> messages) {
        return ContextTrimRequest.forMessageCount(messages, maxMessages);
    }
}
