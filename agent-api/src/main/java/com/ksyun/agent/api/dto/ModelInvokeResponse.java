package com.ksyun.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 开发模型调用响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt，不暴露 Spring AI 对象。
 */
public record ModelInvokeResponse(
        String runId,
        String content,
        List<ToolCallResponse> toolCalls,
        TokenUsageResponse tokenUsage,
        Map<String, Object> metadata
) {
}
