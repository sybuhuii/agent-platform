package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.memory.MemoryCategory;
import com.ksyun.agent.core.memory.MemoryEntry;
import com.ksyun.agent.core.memory.MemoryItem;
import com.ksyun.agent.core.memory.MemoryStoreKey;
import com.ksyun.agent.core.store.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存长期记忆存储实现。
 * <p>
 * 使用 ConcurrentHashMap<MemoryStoreKey, MemoryEntry>，线程安全。
 * 不得使用 static Map、普通 HashMap 配合不完整同步、ThreadLocal 或缓存当前用户。
 * 不得访问 UserStore、SessionStore 或 CheckpointStore。
 * 不得记录完整 value 和 metadata。
 * 不得实现持久化文件写入、自动过期或清理。
 * 不得返回内部 Map 视图。
 * <p>
 * 旧方法（基于 memoryId）暂抛 UnsupportedOperationException，待后续批次适配。
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final ConcurrentHashMap<MemoryStoreKey, MemoryEntry> store = new ConcurrentHashMap<>();

    // --- 旧方法（基于 memoryId，暂不支持） ---

    @Override
    public void put(MemoryItem memoryItem) {
        throw new UnsupportedOperationException(
                "MemoryItem-based put is not supported; use MemoryEntry-based put instead");
    }

    @Override
    public Optional<MemoryItem> get(String userId, String memoryId) {
        throw new UnsupportedOperationException(
                "MemoryItem-based get is not supported; use MemoryEntry-based get instead");
    }

    @Override
    public List<MemoryItem> getByUserId(String userId) {
        throw new UnsupportedOperationException(
                "MemoryItem-based getByUserId is not supported; use MemoryEntry-based list instead");
    }

    @Override
    public void delete(String userId, String memoryId) {
        throw new UnsupportedOperationException(
                "MemoryItem-based delete is not supported; use MemoryEntry-based delete instead");
    }

    // --- 新方法（基于 namespace + key，长期记忆 upsert 语义） ---

    @Override
    public MemoryEntry put(MemoryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("MemoryEntry must not be null");
        }

        MemoryStoreKey storeKey = new MemoryStoreKey(
                entry.userId(), entry.namespace(), entry.key());

        // 使用 compute 实现原子 upsert
        return store.compute(storeKey, (k, existing) -> {
            if (existing == null) {
                // 首次写入：直接保存
                return entry;
            }
            // 更新：保留 memoryId 和 createdAt，更新其他字段
            return new MemoryEntry(
                    existing.memoryId(),            // 保留原 memoryId
                    existing.userId(),              // 保留原 userId
                    existing.namespace(),           // 保留原 namespace
                    existing.key(),                 // 保留原 key
                    entry.value(),                  // 更新 value
                    entry.category(),               // 更新 category
                    entry.metadata(),               // 更新 metadata
                    existing.version() + 1,         // version 加 1
                    existing.createdAt(),           // 保留原 createdAt
                    entry.updatedAt()               // 更新 updatedAt
            );
        });
    }

    @Override
    public Optional<MemoryEntry> get(String userId, String namespace, String key) {
        if (userId == null || userId.isBlank()
                || namespace == null || namespace.isBlank()
                || key == null || key.isBlank()) {
            return Optional.empty();
        }

        MemoryStoreKey storeKey = new MemoryStoreKey(
                userId.trim(), namespace.trim(), key.trim());
        return Optional.ofNullable(store.get(storeKey));
    }

    @Override
    public Collection<MemoryEntry> list(String userId, String namespace) {
        if (userId == null || userId.isBlank()
                || namespace == null || namespace.isBlank()) {
            return List.of();
        }

        String trimmedUserId = userId.trim();
        String trimmedNamespace = namespace.trim();

        // 扫描指定 userId 和 namespace，按 key 升序排列
        ArrayList<MemoryEntry> result = new ArrayList<>();
        for (MemoryEntry entry : store.values()) {
            if (entry.userId().equals(trimmedUserId)
                    && entry.namespace().equals(trimmedNamespace)) {
                result.add(entry);
            }
        }

        result.sort(Comparator.comparing(MemoryEntry::key));
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean delete(String userId, String namespace, String key) {
        if (userId == null || userId.isBlank()
                || namespace == null || namespace.isBlank()
                || key == null || key.isBlank()) {
            return false;
        }

        MemoryStoreKey storeKey = new MemoryStoreKey(
                userId.trim(), namespace.trim(), key.trim());
        return store.remove(storeKey) != null;
    }
}
