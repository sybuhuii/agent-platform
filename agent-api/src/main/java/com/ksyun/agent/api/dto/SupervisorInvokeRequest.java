package com.ksyun.agent.api.dto;

/**
 * 正式 Supervisor 调用请求 DTO。
 * <p>
 * 不允许客户端提交 RunContext、安全身份、systemPrompt、memberAgents 或 maxIterations。
 */
public record SupervisorInvokeRequest(
        String supervisorName,
        String message
) {
}
