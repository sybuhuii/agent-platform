package com.ksyun.agent.core.store;

import com.ksyun.agent.core.memory.MemoryItem;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储接口。
 */
public interface MemoryStore {

    void put(MemoryItem memoryItem);

    Optional<MemoryItem> get(String userId, String memoryId);

    List<MemoryItem> getByUserId(String userId);

    void delete(String userId, String memoryId);
}
