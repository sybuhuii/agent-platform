package com.ksyun.agent.core.message;

/**
 * 工具返回消息。
 *
 * @param toolCallId 工具调用 ID
 * @param toolName   工具名称
 * @param content    返回内容
 * @param error      是否为错误结果
 */
public record ToolAgentMessage(
        String toolCallId,
        String toolName,
        String content,
        boolean error
) implements AgentMessage {
}
