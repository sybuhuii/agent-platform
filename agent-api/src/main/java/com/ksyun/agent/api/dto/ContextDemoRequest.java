package com.ksyun.agent.api.dto;

import java.util.List;

/**
 * 上下文演示请求 DTO。
 * <p>
 * 不得允许客户端提交消息列表、System消息、ToolCall、
 * userId、roles、permissions、maxContextTokens 和摘要配置。
 */
public record ContextDemoRequest(
        int rounds,
        int charactersPerMessage,
        boolean includeToolInteractions,
        boolean invokeModel,
        String finalQuestion
) {
}
