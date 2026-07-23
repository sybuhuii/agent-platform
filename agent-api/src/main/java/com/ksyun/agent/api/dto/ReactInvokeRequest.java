package com.ksyun.agent.api.dto;

/**
 * ReAct 开发调用请求 DTO。
 * <p>
 * 不允许客户端提交 RunContext、安全身份、systemPrompt、allowedTools 或 maxIterations。
 */
public record ReactInvokeRequest(
        String agentName,
        String message
) {
}
