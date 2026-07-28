package com.ksyun.agent.runtime.context;

import java.util.Objects;

/**
 * 摘要触发判断结果，不可变。
 * <p>
 * 约束：
 * - triggerTokenCount = ceil(effectiveMessageBudget * triggerRatio)
 * - currentTokenCount >= triggerTokenCount 时 triggered=true
 * - utilization 不得产生 NaN 或 Infinity
 * - 使用 long 或 BigDecimal 避免中间溢出
 * - 不根据消息数量触发摘要
 * - 不调用模型
 * - 保持无状态和线程安全
 */
public record ContextSummaryTriggerDecision(
        boolean triggered,
        int currentTokenCount,
        int effectiveMessageBudget,
        int triggerTokenCount,
        double utilization
) {

    public ContextSummaryTriggerDecision {
        if (effectiveMessageBudget <= 0) {
            throw new IllegalArgumentException(
                    "effectiveMessageBudget must be > 0, got: " + effectiveMessageBudget);
        }
        if (triggerTokenCount <= 0) {
            throw new IllegalArgumentException(
                    "triggerTokenCount must be > 0, got: " + triggerTokenCount);
        }
        if (Double.isNaN(utilization) || Double.isInfinite(utilization)) {
            throw new IllegalArgumentException("utilization must not be NaN or Infinity");
        }
    }
}
