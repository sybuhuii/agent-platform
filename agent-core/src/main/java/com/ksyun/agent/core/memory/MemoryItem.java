package com.ksyun.agent.core.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 长期记忆条目。
 * <p>
 * 长期记忆必须明确包含 userId。
 *
 * @param memoryId  记忆 ID
 * @param userId    用户 ID
 * @param key       记忆键
 * @param value     记忆值
 * @param metadata  元数据，不可变
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record MemoryItem(
        String memoryId,
        String userId,
        String key,
        String value,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public MemoryItem {
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }
}
