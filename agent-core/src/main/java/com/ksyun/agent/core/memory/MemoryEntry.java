package com.ksyun.agent.core.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 长期记忆条目，不可变。
 * <p>
 * 按 userId + namespace + key 严格隔离。
 * 不得包含 sessionId、threadId、完整 UserSession、RunContext、AgentMessage 列表。
 * 不得包含 Spring AI、LangGraph4j 或 Servlet 类型。
 * 不得将密码、API Key 和 credentialHash 作为合法记忆值。
 * 更新记忆时保留 memoryId 和 createdAt。
 */
public record MemoryEntry(
        String memoryId,
        String userId,
        String namespace,
        String key,
        String value,
        MemoryCategory category,
        Map<String, String> metadata,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public MemoryEntry {
        Objects.requireNonNull(memoryId, "memoryId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (memoryId.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0, got: " + version);
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }

        // trim 所有字符串字段
        memoryId = memoryId.trim();
        userId = userId.trim();
        namespace = namespace.trim();
        key = key.trim();
        value = value.trim();

        // metadata 不可变
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(metadata));
    }
}
