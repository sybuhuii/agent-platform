package com.ksyun.agent.api.dto;

/**
 * 健康检查响应。
 */
public record HealthResponse(
        String status,
        String framework
) {
}
