package com.ksyun.agent.runtime.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 长期记忆上下文追踪，不可变。
 * <p>
 * 只保存安全统计。
 * 不得保存记忆正文、namespace、key、userId、Session ID、异常对象。
 * 字段必须来自真实 Provider 结果。
 */
public record MemoryContextTrace(
        boolean available,
        int totalEntryCount,
        int injectedEntryCount,
        int injectedTokenCount,
        boolean truncated,
        Instant loadedAt
) implements java.io.Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public MemoryContextTrace {
        Objects.requireNonNull(loadedAt, "loadedAt must not be null");
        if (totalEntryCount < 0) {
            throw new IllegalArgumentException("totalEntryCount must be >= 0, got: " + totalEntryCount);
        }
        if (injectedEntryCount < 0) {
            throw new IllegalArgumentException("injectedEntryCount must be >= 0, got: " + injectedEntryCount);
        }
        if (injectedTokenCount < 0) {
            throw new IllegalArgumentException("injectedTokenCount must be >= 0, got: " + injectedTokenCount);
        }
    }

    /**
     * 从 LongTermMemoryContext 创建 Trace。
     */
    public static MemoryContextTrace from(LongTermMemoryContext context, Instant loadedAt) {
        Objects.requireNonNull(context, "context must not be null");
        return new MemoryContextTrace(
                context.message().isPresent(),
                context.totalEntryCount(),
                context.selectedEntryCount(),
                context.estimatedTokens(),
                context.truncated(),
                loadedAt
        );
    }
}
