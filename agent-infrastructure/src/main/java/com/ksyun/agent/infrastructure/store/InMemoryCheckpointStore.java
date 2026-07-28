package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 Checkpoint 存储实现。
 * <p>
 * 双索引 ConcurrentHashMap：
 * - byRunId: runId -> AgentCheckpoint 主索引
 * - byThreadId: threadId -> Set<runId> 辅助索引
 * <p>
 * 并发控制策略：
 * - 使用实例级 ReentrantLock 保证所有写操作的原子性和双索引一致性
 * - 不使用 synchronized(runId.intern())
 * - 不使用 static Map
 * - 读操作无锁，依赖 ConcurrentHashMap 原子方法
 * <p>
 * version 条件更新策略：
 * - save 只负责首次创建
 * - 新 Checkpoint 的 version 必须为 0
 * - 已存在相同 runId 时不得无条件覆盖
 * - 相同内容的 Checkpoint 重复 save 可幂等
 * - 不同内容必须明确冲突
 * - updateIfVersionMatches: expectedVersion 匹配则更新并 version+1，否则返回 false
 * <p>
 * 不自动清理过期 Checkpoint。不添加 @Component，通过 @Bean 装配。
 * delete 不存在时幂等。load 返回完整不可变快照。
 * findPendingByUserId 只返回 userId 精确匹配 + SUSPENDED + PENDING，按 createdAt 升序。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCheckpointStore.class);

    /** runId -> AgentCheckpoint 主索引 */
    private final ConcurrentHashMap<String, AgentCheckpoint> byRunId = new ConcurrentHashMap<>();

    /** threadId -> Set<runId> 辅助索引 */
    private final ConcurrentHashMap<String, java.util.Set<String>> byThreadId = new ConcurrentHashMap<>();

    /** 实例级写锁，保证所有写操作双索引一致 */
    private final Object writeLock = new Object();

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "AgentCheckpoint must not be null");
        }

        String runId = checkpoint.runId();

        synchronized (writeLock) {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
                // 新 Checkpoint，version 必须为 0
                if (checkpoint.version() != 0) {
                    throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                            "New checkpoint version must be 0, got " + checkpoint.version());
                }
                byRunId.put(runId, checkpoint);
            } else {
                // 相同 Checkpoint 重复 save 可幂等
                if (isSameContent(existing, checkpoint)) {
                    log.debug("Checkpoint save idempotent: runId={}, version={}", runId, existing.version());
                    return;
                }
                // 不同内容必须明确冲突
                throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Checkpoint already exists for runId=" + runId
                                + " with different content. Use updateIfVersionMatches for updates.");
            }

            // 维护 threadId 辅助索引
            updateThreadIdIndex(checkpoint.threadId(), runId, null);
        }

        log.debug("Checkpoint saved: runId={}, version={}, status={}",
                runId, checkpoint.version(), checkpoint.status());
    }

    @Override
    public Optional<AgentCheckpoint> load(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint cp = byRunId.get(runId);
        // 返回完整不可变快照（AgentCheckpoint 本身是不可变 record）
        return Optional.ofNullable(cp);
    }

    @Override
    public Optional<AgentCheckpoint> loadByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        java.util.Set<String> runIds = byThreadId.get(threadId);
        if (runIds == null || runIds.isEmpty()) {
            return Optional.empty();
        }
        // 返回最新的一个
        return runIds.stream()
                .map(byRunId::get)
                .filter(cp -> cp != null)
                .max(Comparator.comparing(AgentCheckpoint::updatedAt));
    }

    @Override
    public Collection<AgentCheckpoint> findPendingByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }

        List<AgentCheckpoint> results = new ArrayList<>();
        for (AgentCheckpoint cp : byRunId.values()) {
            // userId 精确匹配
            if (!userId.equals(cp.userId())) {
                continue;
            }
            // CheckpointStatus.SUSPENDED
            if (cp.status() != CheckpointStatus.SUSPENDED) {
                continue;
            }
            // ApprovalStatus.PENDING
            if (cp.pendingApproval() == null || cp.pendingApproval().status() != ApprovalStatus.PENDING) {
                continue;
            }
            results.add(cp);
        }

        // 按 requestedAt 或 createdAt 升序稳定排序
        results.sort(Comparator.comparing(cp ->
                cp.pendingApproval().payload().requestedAt() != null
                        ? cp.pendingApproval().payload().requestedAt()
                        : cp.createdAt()
        ));

        return Collections.unmodifiableList(results);
    }

    @Override
    public boolean updateIfVersionMatches(AgentCheckpoint checkpoint, long expectedVersion) {
        if (checkpoint == null) {
            return false;
        }

        // 新 version 必须严格等于 expectedVersion + 1
        if (checkpoint.version() != expectedVersion + 1) {
            log.warn("Checkpoint version mismatch: expected new version={}, actual={}",
                    expectedVersion + 1, checkpoint.version());
            return false;
        }

        String runId = checkpoint.runId();

        synchronized (writeLock) {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
                // 不存在时不能更新
                return false;
            }

            if (existing.version() != expectedVersion) {
                // version 不匹配
                return false;
            }

            // 稳定身份不得被替换
            if (!existing.runId().equals(checkpoint.runId())
                    || !existing.checkpointId().equals(checkpoint.checkpointId())) {
                log.warn("Checkpoint identity fields changed during update: runId={}", runId);
                return false;
            }

            // 更新主存储
            byRunId.put(runId, checkpoint);

            // threadId 变化时更新索引
            String oldThreadId = existing.threadId();
            String newThreadId = checkpoint.threadId();
            if (!oldThreadId.equals(newThreadId)) {
                updateThreadIdIndex(newThreadId, runId, oldThreadId);
            }

            return true;
        }
    }

    @Override
    public void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }

        synchronized (writeLock) {
            AgentCheckpoint removed = byRunId.remove(runId);
            if (removed != null) {
                // 清理 threadId 辅助索引
                java.util.Set<String> runIds = byThreadId.get(removed.threadId());
                if (runIds != null) {
                    runIds.remove(runId);
                    if (runIds.isEmpty()) {
                        byThreadId.remove(removed.threadId());
                    }
                }
            }
        }
        // 不存在时幂等
    }

    @Override
    public boolean deleteIfVersionMatches(String runId, String checkpointId, long expectedVersion) {
        if (runId == null || runId.isBlank() || checkpointId == null || checkpointId.isBlank()) {
            return false;
        }

        synchronized (writeLock) {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
                // 不存在，幂等返回 false
                return false;
            }

            // 必须同时匹配 checkpointId 和 version
            if (!existing.checkpointId().equals(checkpointId)) {
                log.debug("Conditional delete skipped: checkpointId mismatch. runId={}, expected={}, actual={}",
                        runId, checkpointId, existing.checkpointId());
                return false;
            }

            if (existing.version() != expectedVersion) {
                log.debug("Conditional delete skipped: version mismatch. runId={}, expected={}, actual={}",
                        runId, expectedVersion, existing.version());
                return false;
            }

            AgentCheckpoint removed = byRunId.remove(runId);
            if (removed != null) {
                java.util.Set<String> runIds = byThreadId.get(removed.threadId());
                if (runIds != null) {
                    runIds.remove(runId);
                    if (runIds.isEmpty()) {
                        byThreadId.remove(removed.threadId());
                    }
                }
            }
            return true;
        }
    }

    @Override
    public Collection<AgentCheckpoint> findByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return List.of();
        }

        java.util.Set<String> runIds = byThreadId.get(threadId);
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }

        List<AgentCheckpoint> results = new ArrayList<>();
        for (String runId : runIds) {
            AgentCheckpoint cp = byRunId.get(runId);
            if (cp != null) {
                results.add(cp);
            }
        }

        // 按 updatedAt 升序排列
        results.sort(Comparator.comparing(AgentCheckpoint::updatedAt));

        return Collections.unmodifiableList(results);
    }

    @Override
    public int deleteByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return 0;
        }

        synchronized (writeLock) {
            java.util.Set<String> runIds = byThreadId.remove(threadId);
            if (runIds == null || runIds.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (String runId : runIds) {
                if (byRunId.remove(runId) != null) {
                    count++;
                }
            }

            if (count > 0) {
                log.debug("Checkpoints deleted by threadId: threadId={}, count={}", threadId, count);
            }
            return count;
        }
    }

    /**
     * 更新 threadId 辅助索引。
     * 必须在 writeLock 内调用。
     */
    private void updateThreadIdIndex(String newThreadId, String runId, String oldThreadId) {
        // 添加新索引
        byThreadId.computeIfAbsent(newThreadId, k -> ConcurrentHashMap.newKeySet())
                .add(runId);

        // 清理旧索引（threadId 变化时）
        if (oldThreadId != null && !oldThreadId.equals(newThreadId)) {
            java.util.Set<String> oldRunIds = byThreadId.get(oldThreadId);
            if (oldRunIds != null) {
                oldRunIds.remove(runId);
                if (oldRunIds.isEmpty()) {
                    byThreadId.remove(oldThreadId);
                }
            }
        }
    }

    /**
     * 判断两个 Checkpoint 内容是否相同（用于幂等判断）。
     */
    private boolean isSameContent(AgentCheckpoint a, AgentCheckpoint b) {
        return a.checkpointId().equals(b.checkpointId())
                && a.version() == b.version()
                && a.status() == b.status();
    }
}
