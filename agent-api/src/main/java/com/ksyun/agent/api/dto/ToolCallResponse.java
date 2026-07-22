package com.ksyun.agent.api.dto;

import java.util.Map;

/**
 * 工具调用响应 DTO。
 */
public record ToolCallResponse(
        String id,
        String name,
        Map<String, Object> arguments
) {
}
