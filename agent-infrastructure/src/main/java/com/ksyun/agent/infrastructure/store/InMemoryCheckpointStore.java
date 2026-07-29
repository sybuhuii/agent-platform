package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.ThreadCheckpointKey;
import com.ksyun.agent.core.store.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存 Checkpoint 存储实现。
 * <p>
 * 三索引 ConcurrentHashMap：
 * - byRunId: runId -> AgentCheckpoint 主索引
 * - byThreadId: threadId -> Set<runId> 辅助索引（旧HITL兼容）
 * - byThreadKey: ThreadCheckpointKey(userId,threadId,purpose) -> Set<runId> 线程辅助索引
 * <p>
 * 并发控制策略：
 * - 使用按 runId 分段锁（StripedLock），同一 runId 串行写，不同 runId 并发
 * - 不使用 synchronized(runId.intern())
 * - 不使用 JVM 全局锁
 * - 不使用 ThreadLocal
 * - 读操作无锁，依赖 ConcurrentHashMap 原子方法
 * - 主索引、threadId 索引和 ThreadCheckpointKey 索引在同一分段锁内更新，保持一致
 * - 分段锁使用 ConcurrentHashMap + ReentrantLock，锁对象懒创建并自动清理
 * <p>
 * 稳定身份约束（条件更新时拒绝变化）：
 * - runId, checkpointId, threadId, userId, sessionId, executionType, purpose, agentName, createdAt
 * <p>
 * ThreadCheckpointKey 索引维护：
 * - purpose 发生变化时正确移除旧索引并加入新索引
 * - 辅助索引不得保留已删除 Checkpoint ID
 * - 索引集合为空时删除 ThreadCheckpointKey
 * - 不得让索引 Key 无限残留
 * <p>
 * version 条件更新策略：
 * - save 只负责首次创建
 * - 新 Checkpoint 的 version 必须为 0
 * - 已存在相同 runId 时不得无条件覆盖
 * - 相同完整业务内容重复 save 可幂等
 * - 不同内容必须明确冲突
 * - updateIfVersionMatches: expectedVersion 匹配则更新并 version+1，否则返回 false
 * <p>
 * 幂等判断：必须比较完整业务内容，至少包括稳定身份、status、version、nodeName、
 * pendingApproval、stateData、createdAt、updatedAt、purpose。
 * <p>
 * 不自动清理过期 Checkpoint。不添加 @Component，通过 @Bean 装配。
 * delete 不存在时幂等。load 返回完整不可变快照。
 * findPendingByUserId 只返回 userId 精确匹配 + SUSPENDED + PENDING，按 createdAt 升序。
 * 待审批查询不得返回 THREAD_MEMORY。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCheckpointStore.class);

    /** 分段锁数量，2的幂方便取模 */
    private static final int STRIPE_COUNT = 16;

    /** runId -> AgentCheckpoint 主索引 */
    private final ConcurrentHashMap<String, AgentCheckpoint> byRunId = new ConcurrentHashMap<>();

    /** threadId -> Set<runId> 辅助索引（旧HITL兼容） */
    private final ConcurrentHashMap<String, java.util.Set<String>> byThreadId = new ConcurrentHashMap<>();

    /** ThreadCheckpointKey(userId,threadId,purpose) -> Set<runId> 线程辅助索引 */
    private final ConcurrentHashMap<ThreadCheckpointKey, java.util.Set<String>> byThreadKey = new ConcurrentHashMap<>();

    /** 按 runId hash 分段锁 */
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public InMemoryCheckpointStore() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /** 按 runId 计算分段锁索引 */
    private int stripeIndex(String runId) {
        return runId.hashCode() & (STRIPE_COUNT - 1);
    }

    /** 获取分段锁 */
    private ReentrantLock stripeLock(String runId) {
        return stripes[stripeIndex(runId)];
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "AgentCheckpoint must not be null");
        }

        String runId = checkpoint.runId();
        ReentrantLock lock = stripeLock(runId);
        lock.lock();
        try {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
                // 新 Checkpoint，version 必须为 0
                if (checkpoint.version() != 0) {
                    throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                            "New checkpoint version must be 0, got " + checkpoint.version());
                }
                byRunId.put(runId, checkpoint);
                // 维护旧 threadId 辅助索引
                byThreadId.computeIfAbsent(checkpoint.threadId(), k -> ConcurrentHashMap.newKeySet())
                        .add(runId);
                // 维护线程辅助索引
                addToThreadKeyIndex(checkpoint, runId);
            } else {
                // 相同完整业务内容重复 save 可幂等
                if (isSameContent(existing, checkpoint)) {
                    log.debug("Checkpoint save idempotent: runId={}, version={}", runId, existing.version());
                    return;
                }
                // 不同内容必须明确冲突
                throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Checkpoint already exists for runId=" + runId
                                + " with different content. Use updateIfVersionMatches for updates.");
            }
        } finally {
            lock.unlock();
        }

        log.debug("Checkpoint saved: runId={}, version={}, status={}, purpose={}",
                runId, checkpoint.version(), checkpoint.status(), checkpoint.purpose());
    }

    @Override
    public Optional<AgentCheckpoint> load(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint cp = byRunId.get(runId);
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
        return runIds.stream()
                .map(byRunId::get)
                .filter(Objects::nonNull)
                .max(Comparator.comparing(AgentCheckpoint::updatedAt));
    }

    @Override
    public Collection<AgentCheckpoint> findPendingByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }

        List<AgentCheckpoint> results = new ArrayList<>();
        for (AgentCheckpoint cp : byRunId.values()) {
            if (!userId.equals(cp.userId())) continue;
            if (cp.status() != CheckpointStatus.SUSPENDED) continue;
            if (cp.pendingApproval() == null || cp.pendingApproval().status() != ApprovalStatus.PENDING) continue;
            results.add(cp);
        }

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
        ReentrantLock lock = stripeLock(runId);
        lock.lock();
        try {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
                return false;
            }

            if (existing.version() != expectedVersion) {
                return false;
            }

            // 稳定身份约束：拒绝以下字段变化
            if (!existing.runId().equals(checkpoint.runId())
                    || !existing.checkpointId().equals(checkpoint.checkpointId())
                    || !existing.threadId().equals(checkpoint.threadId())
                    || !existing.userId().equals(checkpoint.userId())
                    || !existing.sessionId().equals(checkpoint.sessionId())
                    || existing.executionType() != checkpoint.executionType()
                    || existing.purpose() != checkpoint.purpose()
                    || !existing.agentName().equals(checkpoint.agentName())
                    || !existing.createdAt().equals(checkpoint.createdAt())) {
                log.warn("Checkpoint stable identity fields changed during update: runId={}", runId);
                return false;
            }

            // threadId 不变化，无需更新旧索引

            // purpose 发生变化时正确移除旧索引并加入新索引
            if (existing.purpose() != checkpoint.purpose()) {
                removeFromThreadKeyIndex(existing, runId);
                addToThreadKeyIndex(checkpoint, runId);
            }

            // 更新主存储
            byRunId.put(runId, checkpoint);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }

        ReentrantLock lock = stripeLock(runId);
        lock.lock();
        try {
            AgentCheckpoint removed = byRunId.remove(runId);
            if (removed != null) {
                removeFromThreadIdIndex(removed.threadId(), runId);
                removeFromThreadKeyIndex(removed, runId);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean deleteIfVersionMatches(String runId, String checkpointId, long expectedVersion) {
        if (runId == null || runId.isBlank() || checkpointId == null || checkpointId.isBlank()) {
            return false;
        }

        ReentrantLock lock = stripeLock(runId);
        lock.lock();
        try {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
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
                removeFromThreadIdIndex(removed.threadId(), runId);
                removeFromThreadKeyIndex(removed, runId);
            }
            return true;
        } finally {
            lock.unlock();
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
        for (String rid : runIds) {
            AgentCheckpoint cp = byRunId.get(rid);
            if (cp != null) {
                results.add(cp);
            }
        }

        results.sort(Comparator.comparing(AgentCheckpoint::updatedAt));
        return Collections.unmodifiableList(results);
    }

    @Override
    public int deleteByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return 0;
        }

        // 需要按每个 runId 获取分段锁
        java.util.Set<String> runIds = byThreadId.remove(threadId);
        if (runIds == null || runIds.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String rid : runIds) {
            ReentrantLock lock = stripeLock(rid);
            lock.lock();
            try {
                AgentCheckpoint removed = byRunId.remove(rid);
                if (removed != null) {
                    removeFromThreadKeyIndex(removed, rid);
                    count++;
                }
            } finally {
                lock.unlock();
            }
        }

        if (count > 0) {
            log.debug("Checkpoints deleted by threadId: threadId={}, count={}", threadId, count);
        }
        return count;
    }

    // --- 新增线程查询方法（按 userId + threadId + purpose） ---

    @Override
    public List<AgentCheckpoint> findByThreadId(String userId, String threadId, CheckpointPurpose purpose) {
        if (userId == null || userId.isBlank()
                || threadId == null || threadId.isBlank()
                || purpose == null) {
            return List.of();
        }

        ThreadCheckpointKey key = new ThreadCheckpointKey(userId.trim(), threadId.trim(), purpose);
        java.util.Set<String> runIds = byThreadKey.get(key);
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }

        // 过滤不存在的主索引记录
        List<AgentCheckpoint> results = new ArrayList<>();
        for (String rid : runIds) {
            AgentCheckpoint cp = byRunId.get(rid);
            if (cp != null) {
                results.add(cp);
            }
        }

        results.sort(Comparator.comparing(AgentCheckpoint::updatedAt));
        return Collections.unmodifiableList(results);
    }

    @Override
    public Optional<AgentCheckpoint> loadLatestByThreadId(String userId, String threadId, CheckpointPurpose purpose) {
        if (userId == null || userId.isBlank()
                || threadId == null || threadId.isBlank()
                || purpose == null) {
            return Optional.empty();
        }

        ThreadCheckpointKey key = new ThreadCheckpointKey(userId.trim(), threadId.trim(), purpose);
        java.util.Set<String> runIds = byThreadKey.get(key);
        if (runIds == null || runIds.isEmpty()) {
            return Optional.empty();
        }

        // 使用稳定比较器：优先 updatedAt，其次 version，其次 checkpointId
        Comparator<AgentCheckpoint> stableComparator = Comparator
                .comparing(AgentCheckpoint::updatedAt, Comparator.reverseOrder())
                .thenComparing(AgentCheckpoint::version, Comparator.reverseOrder())
                .thenComparing(AgentCheckpoint::checkpointId);

        return runIds.stream()
                .map(byRunId::get)
                .filter(Objects::nonNull)
                .max(stableComparator);
    }

    // --- 内部索引维护方法 ---

    /**
     * 从 threadId 辅助索引中移除 runId。必须在对应 runId 的分段锁内调用。
     */
    private void removeFromThreadIdIndex(String threadId, String runId) {
        java.util.Set<String> runIds = byThreadId.get(threadId);
        if (runIds != null) {
            runIds.remove(runId);
            if (runIds.isEmpty()) {
                byThreadId.remove(threadId);
            }
        }
    }

    /**
     * 将 Checkpoint 加入 ThreadCheckpointKey 索引。必须在对应 runId 的分段锁内调用。
     */
    private void addToThreadKeyIndex(AgentCheckpoint checkpoint, String runId) {
        ThreadCheckpointKey key = new ThreadCheckpointKey(
                checkpoint.userId(), checkpoint.threadId(), checkpoint.purpose());
        byThreadKey.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(runId);
    }

    /**
     * 从 ThreadCheckpointKey 索引中移除 runId。必须在对应 runId 的分段锁内调用。
     */
    private void removeFromThreadKeyIndex(AgentCheckpoint checkpoint, String runId) {
        ThreadCheckpointKey key = new ThreadCheckpointKey(
                checkpoint.userId(), checkpoint.threadId(), checkpoint.purpose());
        java.util.Set<String> runIds = byThreadKey.get(key);
        if (runIds != null) {
            runIds.remove(runId);
            if (runIds.isEmpty()) {
                byThreadKey.remove(key);
            }
        }
    }

    /**
     * 判断两个 Checkpoint 完整业务内容是否相同（用于幂等判断）。
     * <p>
     * 必须比较完整业务内容，至少包括：稳定身份、status、version、nodeName、
     * pendingApproval、stateData、createdAt、updatedAt、purpose。
     * 内容不同必须返回 false，不能静默当成相同保存。
     */
    private boolean isSameContent(AgentCheckpoint a, AgentCheckpoint b) {
        return a.checkpointId().equals(b.checkpointId())
                && a.runId().equals(b.runId())
                && a.threadId().equals(b.threadId())
                && a.userId().equals(b.userId())
                && a.sessionId().equals(b.sessionId())
                && a.executionType() == b.executionType()
                && a.purpose() == b.purpose()
                && a.agentName().equals(b.agentName())
                && a.nodeName().equals(b.nodeName())
                && a.status() == b.status()
                && a.version() == b.version()
                && Objects.equals(a.pendingApproval(), b.pendingApproval())
                && Objects.equals(a.stateData(), b.stateData())
                && a.createdAt().equals(b.createdAt())
                && a.updatedAt().equals(b.updatedAt());
    }
}
