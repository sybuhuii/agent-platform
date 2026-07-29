package com.ksyun.agent.core.memory;

import java.util.Objects;

/**
 * 长期记忆存储复合键，不可变。
 * <p>
 * 按 userId + namespace + key 三元组唯一标识一条长期记忆。
 * 不得包含 sessionId、threadId。
 * 不得使用字符串拼接作为 Store 唯一内部 Key。
 * 适合作为 ConcurrentHashMap 的 Key。
 * 不依赖 Spring。
 */
public record MemoryStoreKey(
        String userId,
        String namespace,
        String key
) {

    public MemoryStoreKey {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(key, "key must not be null");

        // 统一 trim
        userId = userId.trim();
        namespace = namespace.trim();
        key = key.trim();

        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
