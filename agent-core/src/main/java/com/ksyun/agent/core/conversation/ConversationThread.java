package com.ksyun.agent.core.conversation;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户可见的会话线索，不可变。
 * <p>
 * 对应产品层面的"一个聊天窗口"，
 * 不是 runtime ThreadConversationState 或 Checkpoint。
 * <p>
 * 不得包含运行时状态、模型上下文、Graph State 或 pending tool calls。
 */
public record ConversationThread(
        String threadId,
        String userId,
        String title,
        boolean pinned,
        boolean archived,
        String agentName,
        Instant createdAt,
        Instant lastMessageAt,
        Instant updatedAt
) {

    public ConversationThread {
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        title = title == null ? "" : title;
        threadId = threadId.trim();
        userId = userId.trim();
        agentName = agentName == null ? null : agentName.trim();
    }
}
