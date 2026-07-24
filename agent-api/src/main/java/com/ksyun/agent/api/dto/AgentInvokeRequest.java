package com.ksyun.agent.api.dto;

/**
 * 正式 Agent 调用请求 DTO。
 * <p>
 * 不允许客户端提交 RunContext、安全身份、systemPrompt、allowedTools 或 maxIterations。
 */
public record AgentInvokeRequest(
        String agentName,
        String message
) {
}
