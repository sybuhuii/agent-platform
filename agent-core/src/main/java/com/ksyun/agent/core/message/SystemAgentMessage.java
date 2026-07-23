package com.ksyun.agent.core.message;

/**
 * 系统消息。
 *
 * @param content 消息内容
 */
public record SystemAgentMessage(String content) implements AgentMessage {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
}
