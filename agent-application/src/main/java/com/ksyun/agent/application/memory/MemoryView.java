package com.ksyun.agent.application.memory;

import com.ksyun.agent.core.memory.MemoryCategory;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 长期记忆视图，不可变。
 * <p>
 * 用于后续 API 和前端，不依赖 Spring Web。
 * 不得返回 userId 给普通前端结果（当前用户已经明确）。
 * 不得返回 sessionId、内部 StoreKey、密码或 credentialHash。
 * 集合和 Map 保持不可变。
 */
public record MemoryView(
        String memoryId,
        String namespace,
        String key,
        String value,
        MemoryCategory category,
        Map<String, String> metadata,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public MemoryView {
        Objects.requireNonNull(memoryId, "memoryId must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // metadata 不可变
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(metadata));
    }

    /**
     * 从 MemoryEntry 构造视图（移除 userId）。
     *
     * @param entry 领域层记忆条目
     * @return 视图对象
     */
    public static MemoryView from(com.ksyun.agent.core.memory.MemoryEntry entry) {
        Objects.requireNonNull(entry, "MemoryEntry must not be null");
        return new MemoryView(
                entry.memoryId(),
                entry.namespace(),
                entry.key(),
                entry.value(),
                entry.category(),
                entry.metadata(),
                entry.version(),
                entry.createdAt(),
                entry.updatedAt()
        );
    }
}
