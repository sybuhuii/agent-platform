package com.ksyun.agent.core.model;

import com.ksyun.agent.core.message.AssistantAgentMessage;

import java.util.Collections;
import java.util.Map;

/**
 * 模型调用响应。
 *
 * @param message    助手消息
 * @param tokenUsage Token 用量
 * @param metadata   元数据，不可变
 */
public record ModelResponse(
        AssistantAgentMessage message,
        TokenUsage tokenUsage,
        Map<String, Object> metadata
) {

    public ModelResponse {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(metadata));
    }
}
