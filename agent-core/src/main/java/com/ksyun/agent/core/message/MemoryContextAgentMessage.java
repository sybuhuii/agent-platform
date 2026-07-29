package com.ksyun.agent.core.message;

import java.time.Instant;
import java.util.Objects;

/**
 * 长期记忆上下文消息，不可变。
 * <p>
 * 仅用于本次模型输入，不得写入完整会话历史、ContextWindowSnapshot 或 THREAD_MEMORY。
 * 不得包含 MemoryEntry 集合、userId、Session ID、namespace 列表之外的内部索引、
 * RunContext、Spring AI 类型或 MemoryStore。
 * 不得创建 MemoryContextAgentMessageV2。
 */
public record MemoryContextAgentMessage(
        String content,
        int entryCount,
        Instant generatedAt
) implements AgentMessage {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public MemoryContextAgentMessage {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        content = content.trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (entryCount <= 0) {
            throw new IllegalArgumentException("entryCount must be > 0, got: " + entryCount);
        }
    }
}
