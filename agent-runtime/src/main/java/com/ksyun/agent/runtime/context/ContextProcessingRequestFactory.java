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
 * - 两个裁剪开关均关闭时，不得静默篡改用户配置
 * - 两个开关均关闭时，返回"无需裁剪"请求
 * - 禁止为了绕过构造器校验而虚构裁剪开关
 */
public class ContextProcessingRequestFactory {

    private final boolean messageCountTrimmingEnabled;
    private final int maxMessages;
    private final boolean tokenTrimmingEnabled;
    private final ContextTokenBudget tokenBudget;
    private final int additionalReservedTokens;
    private final ContextSummaryOptions summaryOptions;

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
     * <p>
     * 严格保留配置中的两个开关值：
     * - 两个开关均关闭时，创建合法的"无需裁剪"请求
     * - 不得静默篡改用户配置
     * - 不得为了绕过构造器校验而虚构裁剪开关
     *
     * @param messages 候选消息列表
     * @return 上下文处理请求，两个开关均关闭时返回"无需裁剪"请求
     */
    public ContextProcessingRequest create(List<AgentMessage> messages) {
        return create(messages, 0);
    }

    /**
     * 为候选消息构造 ContextProcessingRequest，为临时上下文预留额外 Token。
     * <p>
     * additionalReservedTokens 必须大于等于 0，必须进入第七阶段已有 Token 预算计算。
     * 不得通过减小 reservedOutputTokens 为记忆腾空间。
     * 不得忽略记忆 Token。
     * 不得在 ReasonNode 手工构造另一套 ContextProcessingRequest。
     * 配置无效时抛 INVALID_CONTEXT_CONFIGURATION。
     * 不得修改原消息列表。
     *
     * @param messages                  候选消息列表
     * @param additionalReservedTokens  为临时上下文预留的额外 Token 数
     * @return 上下文处理请求
     */
    public ContextProcessingRequest create(List<AgentMessage> messages, int additionalReservedTokens) {
        Objects.requireNonNull(messages, "messages must not be null");
        if (additionalReservedTokens < 0) {
            throw new IllegalArgumentException("additionalReservedTokens must be >= 0, got: " + additionalReservedTokens);
        }

        int effectiveAdditionalReserved = this.additionalReservedTokens + additionalReservedTokens;

        if (!messageCountTrimmingEnabled && !tokenTrimmingEnabled) {
            return ContextProcessingRequest.noTrimming(messages);
        }

        if (!tokenTrimmingEnabled) {
            return ContextProcessingRequest.messageCountOnly(messages, maxMessages);
        }

        if (!messageCountTrimmingEnabled) {
            return ContextProcessingRequest.withSummary(
                    messages, Integer.MAX_VALUE, tokenBudget,
                    effectiveAdditionalReserved,
                    false, true, summaryOptions);
        }

        return ContextProcessingRequest.withSummary(
                messages, maxMessages, tokenBudget,
                effectiveAdditionalReserved,
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
