package com.ksyun.agent.core.model;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.tool.ToolDefinition;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 模型调用请求。
 *
 * @param messages 消息列表，不可变
 * @param tools    可用工具定义列表，不可变
 * @param options  模型选项，不可变
 */
public record ModelRequest(
        List<AgentMessage> messages,
        List<ToolDefinition> tools,
        Map<String, Object> options
) {

    public ModelRequest {
        messages = messages == null
                ? List.of()
                : List.copyOf(messages);

        tools = tools == null
                ? List.of()
                : List.copyOf(tools);

        options = options == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(options));
    }
}
