package com.ksyun.agent.api.dto;

/**
 * Token 用量响应 DTO。
 */
public record TokenUsageResponse(
        long inputTokens,
        long outputTokens,
        long totalTokens
) {
}
