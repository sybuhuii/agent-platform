package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
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
 * 写操作使用 synchronized(runId.intern()) 保证双索引原子性。
 * 读操作无锁，依赖 ConcurrentHashMap 原子方法。
 * <p>
 * version 条件更新策略：
 * - runId 不存在时直接保存，要求 version = 0
 * - runId 已存在时，仅当传入 version 等于已存储 version 时才替换
 * - 替换时 version 递增 1
 * - version 不匹配时抛出 INVALID_ARGUMENT
 * <p>
 * 不自动清理过期 Checkpoint。不添加 @Component，通过 @Bean 装配。
 * findByThreadId 返回按 updatedAt 降序排列的不可变快照。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCheckpointStore.class);

    /** runId -> AgentCheckpoint 主索引 */
    private final ConcurrentHashMap<String, AgentCheckpoint> byRunId = new ConcurrentHashMap<>();

    /** threadId -> Set<runId> 辅助索引 */
    private final ConcurrentHashMap<String, java.util.Set<String>> byThreadId = new ConcurrentHashMap<>();

    @Override
    public void save(AgentCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "AgentCheckpoint must not be null");
        }

        String runId = checkpoint.runId();

        synchronized (runId.intern()) {
            AgentCheckpoint existing = byRunId.get(runId);

            if (existing == null) {
                // 新 Checkpoint，version 必须为 0
                if (checkpoint.version() != 0) {
                    throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                            "New checkpoint version must be 0, got " + checkpoint.version());
                }
                byRunId.put(runId, checkpoint);
            } else {
                // 条件更新：version 必须匹配
                if (checkpoint.version() != existing.version()) {
                    throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                            "Checkpoint version conflict: expected " + existing.version()
                                    + ", got " + checkpoint.version());
                }
                // 替换并自动递增 version
                AgentCheckpoint updated = new AgentCheckpoint(
                        checkpoint.runId(),
                        checkpoint.threadId(),
                        checkpoint.status(),
                        checkpoint.state(),
                        checkpoint.approval(),
                        existing.version() + 1,
                        checkpoint.updatedAt()
                );
                byRunId.put(runId, updated);
            }

            // 维护 threadId 辅助索引
            byThreadId.computeIfAbsent(checkpoint.threadId(), k -> ConcurrentHashMap.newKeySet())
                    .add(runId);
        }

        log.debug("Checkpoint saved: runId={}, version={}, status={}",
                runId, checkpoint.version(), checkpoint.status());
    }

    @Override
    public Optional<AgentCheckpoint> load(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byRunId.get(runId));
    }

    @Override
    public void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }

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

        // 按 updatedAt 降序排列（最新的在前）
        results.sort(Comparator.comparing(AgentCheckpoint::updatedAt).reversed());

        return Collections.unmodifiableList(results);
    }

    @Override
    public int deleteByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return 0;
        }

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
