package com.ksyun.agent.core.conversation;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户可见的会话历史消息，不可变。
 * <p>
 * 仅保存用户输入和 Agent 回复的展示内容，
 * 不保存 Tool/Summary/MemoryContext 等协议消息、
 * 模型原始响应、思维链、完整工具参数或权限集合。
 * <p>
 * 不得用于恢复模型上下文、Graph State 或 pending tool calls。
 * 不得包含 sessionId、RunContext、AgentState 或 Checkpoint 引用。
 */
public record ConversationMessage(
        String messageId,
        String threadId,
        long sequenceNo,
        ConversationMessageRole role,
        String content,
        String deduplicationKey,
        Instant createdAt
) {

    public ConversationMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo must be >= 0");
        }
        messageId = messageId.trim();
        threadId = threadId.trim();
        content = content.trim();
        deduplicationKey = deduplicationKey == null ? null : deduplicationKey.trim();
    }
}
