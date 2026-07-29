package com.ksyun.agent.runtime.memory;

import com.ksyun.agent.core.message.MemoryContextAgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 长期记忆上下文，不可变。
 * <p>
 * 不得返回 MemoryEntry 集合、userId、完整 metadata。
 * message 不得为 null。
 * 没有消息时 selectedEntryCount 和 estimatedTokens 必须为 0。
 * 模型保持不可变。
 */
public record LongTermMemoryContext(
        Optional<MemoryContextAgentMessage> message,
        int totalEntryCount,
        int selectedEntryCount,
        int estimatedTokens,
        boolean truncated,
        List<String> namespaces
) {

    public LongTermMemoryContext {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(namespaces, "namespaces must not be null");
        if (totalEntryCount < 0) {
            throw new IllegalArgumentException("totalEntryCount must be >= 0, got: " + totalEntryCount);
        }
        if (selectedEntryCount < 0) {
            throw new IllegalArgumentException("selectedEntryCount must be >= 0, got: " + selectedEntryCount);
        }
        if (selectedEntryCount > totalEntryCount) {
            throw new IllegalArgumentException("selectedEntryCount must not exceed totalEntryCount");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must be >= 0, got: " + estimatedTokens);
        }
        namespaces = List.copyOf(namespaces);
        // 没有消息时 selectedEntryCount 和 estimatedTokens 必须为 0
        if (message.isEmpty()) {
            if (selectedEntryCount != 0) {
                throw new IllegalArgumentException("selectedEntryCount must be 0 when message is absent");
            }
            if (estimatedTokens != 0) {
                throw new IllegalArgumentException("estimatedTokens must be 0 when message is absent");
            }
        }
    }

    private static final LongTermMemoryContext EMPTY = new LongTermMemoryContext(
            Optional.empty(), 0, 0, 0, false, List.of()
    );

    /**
     * 返回空上下文。
     */
    public static LongTermMemoryContext empty() {
        return EMPTY;
    }
}
