package com.ksyun.agent.core.context;

import java.util.Objects;

/**
 * 上下文 Token 预算，不可变值对象。
 * <p>
 * 有效消息预算 = 模型上下文窗口 - 预留输出Token - 协议预留Token - 安全余量Token
 * <p>
 * 约束：
 * - 所有字段必须 >= 0
 * - maxContextTokens 必须 > 0
 * - availableMessageTokens 由其他字段计算，客户端不能直接指定
 * - availableMessageTokens 必须 > 0
 * - 使用 long 进行中间计算，防止 int 溢出
 * - 最终 Token 数量使用 int 时必须检查范围
 * - 不依赖具体模型供应商类
 * - 不包含 API Key 和模型凭证
 * - 不使用 Map 表达固定预算字段
 */
public record ContextTokenBudget(
        int maxContextTokens,
        int reservedOutputTokens,
        int reservedProtocolTokens,
        int safetyMarginTokens,
        int availableMessageTokens
) {

    public ContextTokenBudget {
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxContextTokens must be > 0, got: " + maxContextTokens);
        }
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens must be >= 0, got: " + reservedOutputTokens);
        }
        if (reservedProtocolTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedProtocolTokens must be >= 0, got: " + reservedProtocolTokens);
        }
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException(
                    "safetyMarginTokens must be >= 0, got: " + safetyMarginTokens);
        }
        if (availableMessageTokens <= 0) {
            throw new IllegalArgumentException(
                    "availableMessageTokens must be > 0, got: " + availableMessageTokens);
        }
    }

    /**
     * 根据模型上下文窗口和各项预留值计算预算。
     * <p>
     * 使用 long 进行中间计算，防止 int 溢出。
     *
     * @param maxContextTokens      模型上下文窗口大小
     * @param reservedOutputTokens  预留输出 Token
     * @param reservedProtocolTokens 协议及工具定义预留 Token
     * @param safetyMarginTokens    安全余量 Token
     * @return 不可变 Token 预算
     * @throws IllegalArgumentException 配置非法时
     */
    public static ContextTokenBudget calculate(
            int maxContextTokens,
            int reservedOutputTokens,
            int reservedProtocolTokens,
            int safetyMarginTokens) {
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxContextTokens must be > 0, got: " + maxContextTokens);
        }
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens must be >= 0, got: " + reservedOutputTokens);
        }
        if (reservedProtocolTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedProtocolTokens must be >= 0, got: " + reservedProtocolTokens);
        }
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException(
                    "safetyMarginTokens must be >= 0, got: " + safetyMarginTokens);
        }

        long available = (long) maxContextTokens
                - (long) reservedOutputTokens
                - (long) reservedProtocolTokens
                - (long) safetyMarginTokens;

        if (available <= 0) {
            throw new IllegalArgumentException(
                    "availableMessageTokens must be > 0: maxContextTokens="
                            + maxContextTokens + " - reservedOutputTokens="
                            + reservedOutputTokens + " - reservedProtocolTokens="
                            + reservedProtocolTokens + " - safetyMarginTokens="
                            + safetyMarginTokens + " = " + available);
        }

        if (available > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "availableMessageTokens exceeds int range: " + available);
        }

        return new ContextTokenBudget(
                maxContextTokens,
                reservedOutputTokens,
                reservedProtocolTokens,
                safetyMarginTokens,
                (int) available
        );
    }
}
