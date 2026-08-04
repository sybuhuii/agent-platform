package com.ksyun.agent.core.conversation;

/**
 * 会话历史消息参与者角色。
 * <p>
 * 仅描述用户可见的产品会话消息角色，
 * 不得与运行时 AgentMessage 协议消息直接对应。
 * 不包含 SYSTEM、TOOL、MEMORY_CONTEXT、SUMMARY 等协议角色。
 */
public enum ConversationMessageRole {

    /** 用户输入 */
    USER,

    /** Agent / Supervisor 回复 */
    ASSISTANT
}
