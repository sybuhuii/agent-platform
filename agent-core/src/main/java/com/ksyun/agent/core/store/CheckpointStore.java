package com.ksyun.agent.core.store;

import com.ksyun.agent.core.run.AgentCheckpoint;

import java.util.Collection;
import java.util.Optional;

/**
 * Checkpoint 存储接口。
 * <p>
 * 位于 agent-core，不依赖 ConcurrentHashMap、Spring、数据库或 Redis。
 * 查询不存在返回 Optional.empty。写入 null 明确拒绝。
 * <p>
 * 本批不实现恢复逻辑，不自动续期，不自动清理过期 Checkpoint。
 */
public interface CheckpointStore {

    /**
     * 保存 Checkpoint。
     * <p>
     * 条件更新：如果已存在同 runId 的 Checkpoint，
     * 仅当传入的 version 等于已存在 Checkpoint 的 version 时才替换并递增 version。
     * version 不匹配时抛出 {@link com.ksyun.agent.core.exception.AgentFrameworkException}
     * (INVALID_ARGUMENT)。
     * runId 不存在时直接保存，version 从 0 开始。
     *
     * @param checkpoint Checkpoint 数据，不得为 null
     */
    void save(AgentCheckpoint checkpoint);

    /**
     * 按 runId 加载最新 Checkpoint。
     *
     * @param runId 运行 ID
     * @return Checkpoint，不存在时返回 Optional.empty
     */
    Optional<AgentCheckpoint> load(String runId);

    /**
     * 按 runId 删除 Checkpoint。
     *
     * @param runId 运行 ID
     */
    void delete(String runId);

    /**
     * 按 threadId 查找所有 Checkpoint。
     * <p>
     * 同一个 threadId 可能对应多次运行（runId 不同）。
     * 返回按 updatedAt 降序排列（最新的在前）。
     *
     * @param threadId 线程 ID
     * @return 该 threadId 下的全部 Checkpoint，不可变
     */
    Collection<AgentCheckpoint> findByThreadId(String threadId);

    /**
     * 按 threadId 删除所有 Checkpoint。
     * <p>
     * 用于线程结束时清理所有历史 Checkpoint。
     *
     * @param threadId 线程 ID
     * @return 实际删除数量
     */
    int deleteByThreadId(String threadId);
}
