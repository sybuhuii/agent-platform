package com.ksyun.agent.core.message;

/**
 * 用户消息。
 *
 * @param content 消息内容
 */
public record UserAgentMessage(String content) implements AgentMessage {
}
