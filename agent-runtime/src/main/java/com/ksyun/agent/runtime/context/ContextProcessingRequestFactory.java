package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingRequest;
import com.ksyun.agent.core.context.ContextSummaryOptions;
import com.ksyun.agent.core.context.ContextTokenBudget;
import com.ksyun.agent.core.message.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * 根据框架上下文配置为候选消息构造 ContextProcessingRequest，纯 Java 实现。
 * <p>
 * 职责：
 * - 使用第七阶段已有配置值
 * - 统一创建消息数限制
 * - 统一创建 Token 预算
 * - 统一创建摘要选项
 * <p>
 * 约束：
 * - 不得在 ReasonNode 中散落配置字段
 * - 不得读取 Spring Environment
 * - 不得依赖 Servlet
 * - 不得保存消息
 * - 不得调用 Pipeline
 * - 保持无状态和线程安全
 * - 基础设施负责把配置值注入构造器
 * - 不得在不同 ReasonNode 构造不同默认预算
 */
public class ContextProcessingRequestFactory {

    private final int maxMessages;
    private final ContextTokenBudget tokenBudget;
    private final boolean messageCountTrimmingEnabled;
    private final boolean tokenTrimmingEnabled;
    private final ContextSummaryOptions summaryOptions;
    private final int additionalReservedTokens;

    public ContextProcessingRequestFactory(
            boolean messageCountTrimmingEnabled,
            int maxMessages,
            boolean tokenTrimmingEnabled,
            ContextTokenBudget tokenBudget,
            int additionalReservedTokens,
            ContextSummaryOptions summaryOptions) {
        this.messageCountTrimmingEnabled = messageCountTrimmingEnabled;
        this.maxMessages = maxMessages;
        this.tokenTrimmingEnabled = tokenTrimmingEnabled;
        this.tokenBudget = tokenBudget;
        this.additionalReservedTokens = additionalReservedTokens;
        this.summaryOptions = summaryOptions != null ? summaryOptions : ContextSummaryOptions.disabled();
    }

    /**
     * 为候选消息构造 ContextProcessingRequest。
     *
     * @param messages 候选消息列表
     * @return 上下文处理请求
     */
    public ContextProcessingRequest create(List<AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");

        if (!messageCountTrimmingEnabled && !tokenTrimmingEnabled) {
            // 至少启用一种裁剪策略，默认启用消息数
            return ContextProcessingRequest.messageCountOnly(messages, maxMessages);
        }

        if (!tokenTrimmingEnabled) {
            // 仅消息数裁剪
            return ContextProcessingRequest.messageCountOnly(messages, maxMessages);
        }

        if (!messageCountTrimmingEnabled) {
            // 仅 Token 裁剪
            return ContextProcessingRequest.withSummary(
                    messages, Integer.MAX_VALUE, tokenBudget,
                    additionalReservedTokens,
                    false, true, summaryOptions);
        }

        // 双重裁剪 + 摘要选项
        return ContextProcessingRequest.withSummary(
                messages, maxMessages, tokenBudget,
                additionalReservedTokens,
                true, true, summaryOptions);
    }

    /**
     * 判断上下文处理是否启用。
     *
     * @return 如果至少启用一种裁剪策略则返回 true
     */
    public boolean isContextProcessingEnabled() {
        return messageCountTrimmingEnabled || tokenTrimmingEnabled;
    }
}
