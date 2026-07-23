package com.ksyun.agent.core.message;

import java.io.Serializable;

/**
 * Agent 消息密封接口,只有permits后面的类可以实现
 * <p>
 * 框架无关，不直接使用 Spring AI 的 Message 类型。
 */
public sealed interface AgentMessage
        extends Serializable
        permits SystemAgentMessage, UserAgentMessage, AssistantAgentMessage, ToolAgentMessage {
}
