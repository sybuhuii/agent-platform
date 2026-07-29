package com.ksyun.agent.application.memory;

import com.ksyun.agent.core.memory.MemoryCategory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 长期记忆写入命令，不可变。
 * <p>
 * 不得包含 userId（来自已认证 UserSession）。
 * 不得包含 memoryId、version、createdAt、updatedAt（由服务端管理）。
 * 不得包含 sessionId 或 threadId。
 * 不得包含 HTTP 对象、模型响应或任意 Agent 消息列表。
 */
public record MemoryWriteCommand(
        String namespace,
        String key,
        String value,
        MemoryCategory category,
        Map<String, String> metadata
) {

    public MemoryWriteCommand {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(category, "category must not be null");

        // metadata 不可变
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(metadata));
    }
}
