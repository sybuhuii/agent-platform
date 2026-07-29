package com.ksyun.agent.core.store;

import com.ksyun.agent.core.memory.MemoryEntry;
import com.ksyun.agent.core.memory.MemoryItem;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 记忆存储接口。
 * <p>
 * 旧方法基于 userId + memoryId，保留兼容。
 * 新方法基于 userId + namespace + key，支持长期记忆 upsert 语义。
 * <p>
 * 接口不依赖 Spring、数据库、模型或 CheckpointStore。
 * 不得返回 null。
 */
public interface MemoryStore {

    // --- 旧方法（基于 memoryId，保留兼容） ---

    void put(MemoryItem memoryItem);

    Optional<MemoryItem> get(String userId, String memoryId);

    List<MemoryItem> getByUserId(String userId);

    void delete(String userId, String memoryId);

    // --- 新方法（基于 namespace + key，长期记忆 upsert 语义） ---

    /**
     * 写入或更新长期记忆条目。
     * <p>
     * 相同 userId + namespace + key 视为同一记忆（upsert 语义）：
     * <ul>
     *   <li>首次写入创建记录</li>
     *   <li>再次写入更新 value、category、metadata 和 updatedAt</li>
     *   <li>更新时 version 加 1</li>
     *   <li>更新时 memoryId 和 createdAt 保持不变</li>
     * </ul>
     *
     * @param entry 记忆条目
     * @return 写入后的完整 MemoryEntry
     */
    MemoryEntry put(MemoryEntry entry);

    /**
     * 按复合键查询长期记忆。
     *
     * @param userId    用户 ID
     * @param namespace 命名空间
     * @param key       记忆键
     * @return 记忆条目，不存在返回 Optional.empty()
     */
    Optional<MemoryEntry> get(String userId, String namespace, String key);

    /**
     * 列出指定用户和命名空间下的所有记忆。
     *
     * @param userId    用户 ID
     * @param namespace 命名空间
     * @return 不可变快照，按 key 升序排列
     */
    Collection<MemoryEntry> list(String userId, String namespace);

    /**
     * 删除指定复合键的记忆。
     *
     * @param userId    用户 ID
     * @param namespace 命名空间
     * @param key       记忆键
     * @return true 表示已删除，false 表示不存在
     */
    boolean delete(String userId, String namespace, String key);
}
