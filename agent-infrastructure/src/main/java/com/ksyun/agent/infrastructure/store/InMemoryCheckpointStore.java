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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存 Checkpoint 存储实现。
 *
 * checkpointId 是主存储键。
 * runId、threadId、ThreadCheckpointKey 都是辅助索引。
 *
 * 同一个 runId 可以同时存在：
 * - HITL_RECOVERY
 * - THREAD_MEMORY
 *
 * 写操作按 runId 分段串行，读操作返回不可变快照。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger log =
            LoggerFactory.getLogger(InMemoryCheckpointStore.class);

    private static final int STRIPE_COUNT = 16;

    /**
     * checkpointId -> AgentCheckpoint。
     */
    private final ConcurrentHashMap<String, AgentCheckpoint> byCheckpointId =
            new ConcurrentHashMap<>();

    /**
     * runId -> checkpointId 集合。
     */
    private final ConcurrentHashMap<String, Set<String>> byRunId =
            new ConcurrentHashMap<>();

    /**
     * threadId -> checkpointId 集合。
     */
    private final ConcurrentHashMap<String, Set<String>> byThreadId =
            new ConcurrentHashMap<>();

    /**
     * userId + threadId + purpose -> checkpointId 集合。
     */
    private final ConcurrentHashMap<ThreadCheckpointKey, Set<String>> byThreadKey =
            new ConcurrentHashMap<>();

    private final ReentrantLock[] stripes =
            new ReentrantLock[STRIPE_COUNT];

    public InMemoryCheckpointStore() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "AgentCheckpoint must not be null");
        }

        if (checkpoint.version() != 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "New checkpoint version must be 0, got "
                            + checkpoint.version());
        }

        ReentrantLock lock = stripeLock(checkpoint.runId());
        lock.lock();

        try {
            AgentCheckpoint existing =
                    byCheckpointId.get(checkpoint.checkpointId());

            if (existing != null) {
                if (isSameContent(existing, checkpoint)) {
                    log.debug(
                            "Checkpoint save idempotent: checkpointId={}, runId={}",
                            checkpoint.checkpointId(),
                            checkpoint.runId());
                    return;
                }

                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Checkpoint already exists for checkpointId="
                                + checkpoint.checkpointId());
            }

            byCheckpointId.put(checkpoint.checkpointId(), checkpoint);
            addIndexes(checkpoint);
        } finally {
            lock.unlock();
        }

        log.debug(
                "Checkpoint saved: checkpointId={}, runId={}, version={}, "
                        + "status={}, purpose={}",
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.version(),
                checkpoint.status(),
                checkpoint.purpose());
    }

    /**
     * 按 runId 加载时优先返回 HITL_RECOVERY。
     *
     * 这样审批和恢复接口不会在 HITL 与 THREAD_MEMORY 短暂共存期间
     * 错误地读取 THREAD_MEMORY。
     */
    @Override
    public Optional<AgentCheckpoint> load(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }

        Set<String> checkpointIds = byRunId.get(runId);
        if (checkpointIds == null || checkpointIds.isEmpty()) {
            return Optional.empty();
        }

        return checkpointIds.stream()
                .map(byCheckpointId::get)
                .filter(Objects::nonNull)
                .max(runLookupComparator());
    }

    @Override
    public Optional<AgentCheckpoint> loadByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }

        Set<String> checkpointIds = byThreadId.get(threadId);
        if (checkpointIds == null || checkpointIds.isEmpty()) {
            return Optional.empty();
        }

        return checkpointIds.stream()
                .map(byCheckpointId::get)
                .filter(Objects::nonNull)
                .max(stableComparator());
    }

    @Override
    public Collection<AgentCheckpoint> findPendingByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }

        List<AgentCheckpoint> results = new ArrayList<>();

        for (AgentCheckpoint checkpoint : byCheckpointId.values()) {
            if (!userId.equals(checkpoint.userId())) {
                continue;
            }
            if (checkpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
                continue;
            }
            if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
                continue;
            }
            if (checkpoint.pendingApproval() == null
                    || checkpoint.pendingApproval().status()
                    != ApprovalStatus.PENDING) {
                continue;
            }

            results.add(checkpoint);
        }

        results.sort(
                Comparator.comparing(checkpoint ->
                        checkpoint.pendingApproval()
                                .payload()
                                .requestedAt() != null
                                ? checkpoint.pendingApproval()
                                .payload()
                                .requestedAt()
                                : checkpoint.createdAt()));

        return List.copyOf(results);
    }

    @Override
    public boolean updateIfVersionMatches(
            AgentCheckpoint checkpoint,
            long expectedVersion
    ) {
        if (checkpoint == null) {
            return false;
        }

        if (checkpoint.version() != expectedVersion + 1) {
            return false;
        }

        ReentrantLock lock = stripeLock(checkpoint.runId());
        lock.lock();

        try {
            AgentCheckpoint existing =
                    byCheckpointId.get(checkpoint.checkpointId());

            if (existing == null
                    || existing.version() != expectedVersion) {
                return false;
            }

            if (!hasSameStableIdentity(existing, checkpoint)) {
                log.warn(
                        "Checkpoint stable identity changed: checkpointId={}, runId={}",
                        checkpoint.checkpointId(),
                        checkpoint.runId());
                return false;
            }

            /*
             * purpose 允许在条件更新中变化。
             * 先移除旧辅助索引，再加入新辅助索引。
             */
            removeIndexes(existing);
            byCheckpointId.put(checkpoint.checkpointId(), checkpoint);
            addIndexes(checkpoint);

            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按 runId 删除该次运行下的全部 Checkpoint。
     */
    @Override
    public void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }

        ReentrantLock lock = stripeLock(runId);
        lock.lock();

        try {
            Set<String> checkpointIds = byRunId.get(runId);
            if (checkpointIds == null || checkpointIds.isEmpty()) {
                return;
            }

            for (String checkpointId : List.copyOf(checkpointIds)) {
                AgentCheckpoint removed =
                        byCheckpointId.remove(checkpointId);

                if (removed != null) {
                    removeIndexes(removed);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean deleteIfVersionMatches(
            String runId,
            String checkpointId,
            long expectedVersion
    ) {
        if (runId == null || runId.isBlank()
                || checkpointId == null || checkpointId.isBlank()) {
            return false;
        }

        ReentrantLock lock = stripeLock(runId);
        lock.lock();

        try {
            AgentCheckpoint existing =
                    byCheckpointId.get(checkpointId);

            if (existing == null
                    || !runId.equals(existing.runId())
                    || existing.version() != expectedVersion) {
                return false;
            }

            if (!byCheckpointId.remove(checkpointId, existing)) {
                return false;
            }

            removeIndexes(existing);
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

        Set<String> checkpointIds = byThreadId.get(threadId);
        if (checkpointIds == null || checkpointIds.isEmpty()) {
            return List.of();
        }

        List<AgentCheckpoint> results = checkpointIds.stream()
                .map(byCheckpointId::get)
                .filter(Objects::nonNull)
                .filter(checkpoint ->
                        threadId.equals(checkpoint.threadId()))
                .sorted(stableComparator())
                .toList();

        return List.copyOf(results);
    }

    @Override
    public int deleteByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return 0;
        }

        Set<String> checkpointIds = byThreadId.get(threadId);
        if (checkpointIds == null || checkpointIds.isEmpty()) {
            return 0;
        }

        int deleted = 0;

        for (String checkpointId : List.copyOf(checkpointIds)) {
            AgentCheckpoint checkpoint =
                    byCheckpointId.get(checkpointId);

            if (checkpoint == null
                    || !threadId.equals(checkpoint.threadId())) {
                continue;
            }

            if (deleteIfVersionMatches(
                    checkpoint.runId(),
                    checkpoint.checkpointId(),
                    checkpoint.version())) {
                deleted++;
            }
        }

        return deleted;
    }

    @Override
    public List<AgentCheckpoint> findByThreadId(
            String userId,
            String threadId,
            CheckpointPurpose purpose
    ) {
        if (userId == null || userId.isBlank()
                || threadId == null || threadId.isBlank()
                || purpose == null) {
            return List.of();
        }

        String normalizedUserId = userId.trim();
        String normalizedThreadId = threadId.trim();

        ThreadCheckpointKey key = new ThreadCheckpointKey(
                normalizedUserId,
                normalizedThreadId,
                purpose);

        Set<String> checkpointIds = byThreadKey.get(key);
        if (checkpointIds == null || checkpointIds.isEmpty()) {
            return List.of();
        }

        List<AgentCheckpoint> results = checkpointIds.stream()
                .map(byCheckpointId::get)
                .filter(Objects::nonNull)
                .filter(checkpoint ->
                        normalizedUserId.equals(checkpoint.userId()))
                .filter(checkpoint ->
                        normalizedThreadId.equals(checkpoint.threadId()))
                .filter(checkpoint ->
                        checkpoint.purpose() == purpose)
                .sorted(stableComparator())
                .toList();

        return List.copyOf(results);
    }

    @Override
    public Optional<AgentCheckpoint> loadLatestByThreadId(
            String userId,
            String threadId,
            CheckpointPurpose purpose
    ) {
        return findByThreadId(userId, threadId, purpose)
                .stream()
                .max(stableComparator());
    }

    private void addIndexes(AgentCheckpoint checkpoint) {
        String checkpointId = checkpoint.checkpointId();

        byRunId.computeIfAbsent(
                        checkpoint.runId(),
                        ignored -> ConcurrentHashMap.newKeySet())
                .add(checkpointId);

        byThreadId.computeIfAbsent(
                        checkpoint.threadId(),
                        ignored -> ConcurrentHashMap.newKeySet())
                .add(checkpointId);

        ThreadCheckpointKey threadKey = new ThreadCheckpointKey(
                checkpoint.userId(),
                checkpoint.threadId(),
                checkpoint.purpose());

        byThreadKey.computeIfAbsent(
                        threadKey,
                        ignored -> ConcurrentHashMap.newKeySet())
                .add(checkpointId);
    }

    private void removeIndexes(AgentCheckpoint checkpoint) {
        String checkpointId = checkpoint.checkpointId();

        removeIndexValue(
                byRunId,
                checkpoint.runId(),
                checkpointId);

        removeIndexValue(
                byThreadId,
                checkpoint.threadId(),
                checkpointId);

        ThreadCheckpointKey threadKey = new ThreadCheckpointKey(
                checkpoint.userId(),
                checkpoint.threadId(),
                checkpoint.purpose());

        removeIndexValue(
                byThreadKey,
                threadKey,
                checkpointId);
    }

    private <K> void removeIndexValue(
            ConcurrentHashMap<K, Set<String>> index,
            K key,
            String checkpointId
    ) {
        index.computeIfPresent(key, (ignored, values) -> {
            values.remove(checkpointId);
            return values.isEmpty() ? null : values;
        });
    }

    private boolean hasSameStableIdentity(
            AgentCheckpoint existing,
            AgentCheckpoint updated
    ) {
        return existing.checkpointId()
                .equals(updated.checkpointId())
                && existing.runId().equals(updated.runId())
                && existing.threadId().equals(updated.threadId())
                && existing.userId().equals(updated.userId())
                && existing.executionType()
                == updated.executionType()
                && existing.agentName()
                .equals(updated.agentName())
                && existing.createdAt()
                .equals(updated.createdAt());
    }

    private boolean isSameContent(
            AgentCheckpoint first,
            AgentCheckpoint second
    ) {
        return first.checkpointId().equals(second.checkpointId())
                && first.runId().equals(second.runId())
                && first.threadId().equals(second.threadId())
                && first.userId().equals(second.userId())
                && first.executionType()
                == second.executionType()
                && first.purpose() == second.purpose()
                && first.agentName().equals(second.agentName())
                && first.nodeName().equals(second.nodeName())
                && first.status() == second.status()
                && first.version() == second.version()
                && Objects.equals(
                first.pendingApproval(),
                second.pendingApproval())
                && Objects.equals(
                first.stateData(),
                second.stateData())
                && first.createdAt().equals(second.createdAt())
                && first.updatedAt().equals(second.updatedAt());
    }

    private Comparator<AgentCheckpoint> stableComparator() {
        return Comparator
                .comparing(AgentCheckpoint::updatedAt)
                .thenComparingLong(AgentCheckpoint::version)
                .thenComparing(AgentCheckpoint::checkpointId);
    }

    private Comparator<AgentCheckpoint> runLookupComparator() {
        return Comparator
                .comparingInt((AgentCheckpoint checkpoint) ->
                        checkpoint.purpose()
                                == CheckpointPurpose.HITL_RECOVERY
                                ? 1
                                : 0)
                .thenComparing(stableComparator());
    }

    private ReentrantLock stripeLock(String runId) {
        int index = runId.hashCode() & (STRIPE_COUNT - 1);
        return stripes[index];
    }
}