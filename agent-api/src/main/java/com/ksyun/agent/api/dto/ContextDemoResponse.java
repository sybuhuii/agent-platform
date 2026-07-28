package com.ksyun.agent.api.dto;

import java.util.List;

/**
 * 上下文演示响应 DTO。
 * <p>
 * 不暴露完整消息、摘要正文、Prompt、ToolCall参数、Session ID。
 */
public record ContextDemoResponse(
        String runId,
        int originalMessageCount,
        int processedMessageCount,
        int originalTokenCount,
        int processedTokenCount,
        int effectiveMessageBudget,
        boolean messageCountTrimmed,
        boolean tokenTrimmed,
        boolean summaryTriggered,
        boolean summaryApplied,
        int summarizedMessageCount,
        List<String> diagnostics,
        boolean modelInvoked,
        String modelContent,
        String modelErrorCode
) {
}
