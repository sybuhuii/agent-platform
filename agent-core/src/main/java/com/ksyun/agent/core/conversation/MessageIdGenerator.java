package com.ksyun.agent.core.conversation;

/**
 * 会话消息 ID 生成器 SPI。
 * <p>
 * 位于 agent-core，不依赖 Spring 或数据库。
 * 实现负责生成全局唯一的 messageId，不含 userId、threadId 或敏感信息。
 */
public interface MessageIdGenerator {

    String nextMessageId();
}
