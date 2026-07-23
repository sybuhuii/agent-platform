package com.ksyun.agent.api.dto;

/**
 * Supervisor 开发调用请求 DTO。
 * <p>
 * 不允许客户端提交 RunContext、安全身份、systemPrompt、memberAgents 或 maxIterations。
 */
public record SupervisorDevInvokeRequest(
        String supervisorName,
        String message
) {
}
