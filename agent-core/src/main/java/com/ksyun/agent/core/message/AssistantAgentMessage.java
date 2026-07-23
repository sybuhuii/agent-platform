package com.ksyun.agent.core.message;

import com.ksyun.agent.core.tool.ToolCall;

import java.util.Collections;
import java.util.List;

/**
 * 助手消息。
 *
 * @param content   消息内容
 * @param toolCalls 工具调用列表，不可变
 */
public record AssistantAgentMessage(
        String content,
        List<ToolCall> toolCalls
) implements AgentMessage {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public AssistantAgentMessage {
        toolCalls = toolCalls == null ? List.of() : Collections.unmodifiableList(toolCalls);
    }
}
