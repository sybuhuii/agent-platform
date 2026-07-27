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
 * 语义：
 * - save 只负责首次创建
 * - 新 Checkpoint 的 version 必须为 0
 * - 已存在相同 runId 时不得无条件覆盖
 * - 相同 Checkpoint 重复 save 可幂等；不同内容必须明确冲突
 * - 正常版本冲突通过 updateIfVersionMatches 返回 false
 * - 成功更新后的 version 必须为 expectedVersion + 1
 * - 查询不存在返回 Optional.empty
 * - 查询集合返回不可变快照
 * - Store 不负责生成 ID、审批决策、执行恢复、调用模型或工具
 */
public interface CheckpointStore {

    /**
     * 保存 Checkpoint（首次创建）。
     * <p>
     * 新 Checkpoint 的 version 必须为 0。
     * 相同 runId 已存在时不得无条件覆盖。
     * 相同内容的 Checkpoint 重复 save 可幂等返回。
     *
     * @param checkpoint Checkpoint 数据，不得为 null
     */
    void save(AgentCheckpoint checkpoint);

    /**
     * 按 runId 加载 Checkpoint。
     * <p>
     * 返回完整不可变快照。
     *
     * @param runId 运行 ID
     * @return Checkpoint，不存在时返回 Optional.empty
     */
    Optional<AgentCheckpoint> load(String runId);

    /**
     * 按 threadId 加载 Checkpoint。
     *
     * @param threadId 线程 ID
     * @return Checkpoint，不存在时返回 Optional.empty
     */
    Optional<AgentCheckpoint> loadByThreadId(String threadId);

    /**
     * 按 userId 查找当前待审批 Checkpoint。
     * <p>
     * 只返回 userId 精确匹配、CheckpointStatus.SUSPENDED、ApprovalStatus.PENDING 的记录。
     * 按 requestedAt 或 createdAt 升序稳定排序。
     * 不同用户不得串读。
     *
     * @param userId 用户 ID
     * @return 不可变快照
     */
    Collection<AgentCheckpoint> findPendingByUserId(String userId);

    /**
     * 条件更新：仅当 expectedVersion 匹配已存储 version 时才替换。
     * <p>
     * 成功更新后 version 必须为 expectedVersion + 1。
     * expectedVersion 不匹配时返回 false，不使用异常作为正常控制流。
     *
     * @param checkpoint       新 Checkpoint 数据
     * @param expectedVersion  期望的当前版本号
     * @return true 表示更新成功，false 表示版本不匹配
     */
    boolean updateIfVersionMatches(AgentCheckpoint checkpoint, long expectedVersion);

    /**
     * 按 runId 删除 Checkpoint。
     * <p>
     * 不存在时幂等。
     *
     * @param runId 运行 ID
     */
    void delete(String runId);

    /**
     * 按 threadId 查找所有 Checkpoint（兼容保留）。
     * <p>
     * 同一个 threadId 可能对应多次运行（runId 不同）。
     * 返回不可变快照。
     *
     * @param threadId 线程 ID
     * @return 该 threadId 下的全部 Checkpoint，不可变
     */
    Collection<AgentCheckpoint> findByThreadId(String threadId);

    /**
     * 按 threadId 删除所有 Checkpoint（兼容保留）。
     *
     * @param threadId 线程 ID
     * @return 实际删除数量
     */
    int deleteByThreadId(String threadId);
}
