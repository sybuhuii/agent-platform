package com.ksyun.agent.core.model;

/**
 * Token 用量统计。
 *
 * @param inputTokens  输入 Token 数
 * @param outputTokens 输出 Token 数
 * @param totalTokens  总 Token 数
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens
) {
}
