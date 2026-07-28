package com.ksyun.agent.runtime.context;

/**
 * 摘要触发器，纯 Java 实现。
 * <p>
 * 触发规则：
 * - triggerTokenCount = ceil(effectiveMessageBudget * triggerRatio)
 * - currentTokenCount >= triggerTokenCount 时触发
 * - currentTokenCount 低于阈值时不触发
 * - triggerRatio 必须 > 0 且 < 1
 * - effectiveMessageBudget 必须 > 0
 * - utilization 计算不得产生 NaN 或 Infinity
 * - 使用 long 遏免中间溢出
 * - 不根据消息数量触发摘要
 * - 不调用模型
 * - 保持无状态和线程安全
 */
public class ContextSummaryTrigger {

    /**
     * 评估是否应触发摘要。
     *
     * @param currentTokenCount       当前上下文 Token 数
     * @param effectiveMessageBudget  有效消息预算
     * @param triggerRatio            触发比例，必须 > 0 且 < 1
     * @return 触发判断结果
     */
    public ContextSummaryTriggerDecision evaluate(
            int currentTokenCount,
            int effectiveMessageBudget,
            double triggerRatio) {

        if (effectiveMessageBudget <= 0) {
            throw new IllegalArgumentException(
                    "effectiveMessageBudget must be > 0, got: " + effectiveMessageBudget);
        }
        if (triggerRatio <= 0 || triggerRatio >= 1) {
            throw new IllegalArgumentException(
                    "triggerRatio must be > 0 and < 1, got: " + triggerRatio);
        }

        // 使用 long 避免溢出
        long budgetLong = effectiveMessageBudget;
        long triggerTokenCountLong = (long) Math.ceil(budgetLong * triggerRatio);
        int triggerTokenCount = triggerTokenCountLong > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) triggerTokenCountLong;

        boolean triggered = currentTokenCount >= triggerTokenCount;

        // utilization 计算，避免 NaN 和 Infinity
        double utilization = effectiveMessageBudget > 0
                ? (double) currentTokenCount / effectiveMessageBudget : 0.0;

        return new ContextSummaryTriggerDecision(
                triggered, currentTokenCount, effectiveMessageBudget, triggerTokenCount, utilization);
    }
}
