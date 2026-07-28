package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextTokenBudget;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;

/**
 * 上下文 Token 预算计算器，纯 Java 实现。
 * <p>
 * 职责：
 * - 校验全部预算配置
 * - 计算 availableMessageTokens
 * - 配置非法时抛 INVALID_CONTEXT_CONFIGURATION
 * <p>
 * 约束：
 * - 不得自动把负数修正为 0
 * - 不得自动放大模型窗口
 * - 不得调用 TokenCounter
 * - 不得调用模型
 * - 保持无状态和线程安全
 */
public class ContextTokenBudgetCalculator {

    /**
     * 根据模型上下文窗口和各项预留值计算 Token 预算。
     *
     * @param maxContextTokens       模型上下文窗口大小，必须 > 0
     * @param reservedOutputTokens   预留输出 Token，必须 >= 0
     * @param reservedProtocolTokens 协议及工具定义预留 Token，必须 >= 0
     * @param safetyMarginTokens     安全余量 Token，必须 >= 0
     * @return 不可变 Token 预算
     * @throws AgentFrameworkException 配置非法时
     */
    public ContextTokenBudget calculate(
            int maxContextTokens,
            int reservedOutputTokens,
            int reservedProtocolTokens,
            int safetyMarginTokens) {

        // 校验 maxContextTokens
        if (maxContextTokens <= 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    "maxContextTokens must be > 0, got: " + maxContextTokens);
        }

        // 校验预留值
        if (reservedOutputTokens < 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    "reservedOutputTokens must be >= 0, got: " + reservedOutputTokens);
        }

        if (reservedProtocolTokens < 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    "reservedProtocolTokens must be >= 0, got: " + reservedProtocolTokens);
        }

        if (safetyMarginTokens < 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    "safetyMarginTokens must be >= 0, got: " + safetyMarginTokens);
        }

        // 校验总预留不超过窗口
        // maxContextTokens > reservedOutputTokens + reservedProtocolTokens + safetyMarginTokens
        long totalReserved = (long) reservedOutputTokens
                + (long) reservedProtocolTokens
                + (long) safetyMarginTokens;

        if (totalReserved >= maxContextTokens) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    "Total reserved tokens (" + totalReserved
                            + ") must be less than maxContextTokens ("
                            + maxContextTokens + ")");
        }

        try {
            return ContextTokenBudget.calculate(
                    maxContextTokens,
                    reservedOutputTokens,
                    reservedProtocolTokens,
                    safetyMarginTokens);
        } catch (IllegalArgumentException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                    e.getMessage(), e);
        }
    }
}
