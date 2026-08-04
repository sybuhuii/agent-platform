package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.store.ToolAuditStore;
import com.ksyun.agent.core.tool.audit.ToolAuditSnapshot;
import com.ksyun.agent.core.tool.audit.ToolAuditStatus;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditCompletion;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存工具调用审计存储实现。
 * <p>
 * 行为与 {@link PostgresToolAuditStore} 的公开语义一致：
 * <ul>
 *   <li>{@link #start} 创建语义：相同 auditId 相同内容幂等，不同内容冲突。</li>
 *   <li>{@link #complete} 只允许 STARTED → 终态。相同终态重复提交幂等；不同终态冲突。</li>
 *   <li>返回不可变快照。</li>
 * </ul>
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap#compute} 保证原子性。
 * 不添加 @Component，通过 @Bean 装配。
 */
public class InMemoryToolAuditStore implements ToolAuditStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryToolAuditStore.class);

    private final ConcurrentHashMap<String, ToolAuditSnapshot> store = new ConcurrentHashMap<>();

    @Override
    public ToolAuditSnapshot start(ToolInvocationAuditStarted started) {
        Objects.requireNonNull(started, "started must not be null");

        ToolAuditSnapshot newSnapshot = toSnapshot(started);

        ToolAuditSnapshot[] result = new ToolAuditSnapshot[1];
        store.compute(started.auditId(), (auditId, existing) -> {
            if (existing == null) {
                result[0] = newSnapshot;
                return newSnapshot;
            }

            // 相同 auditId + 相同内容 → 幂等返回
            if (isSameStartContent(existing, newSnapshot)) {
                result[0] = existing;
                return existing;
            }

            // 相同 auditId + 不同内容 → 冲突
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Tool audit start conflict for auditId: " + auditId);
        });

        return result[0];
    }

    @Override
    public ToolAuditSnapshot complete(ToolInvocationAuditCompletion completion) {
        Objects.requireNonNull(completion, "completion must not be null");

        ToolAuditSnapshot[] result = new ToolAuditSnapshot[1];
        store.compute(completion.auditId(), (auditId, existing) -> {
            if (existing == null) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Tool audit not found for auditId: " + auditId);
            }

            // 已是终态
            if (existing.isTerminal()) {
                // 相同终态 + 相同内容 → 幂等返回
                if (isSameTerminalContent(existing, completion)) {
                    result[0] = existing;
                    return existing;
                }
                // 不同终态 → 冲突
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Tool audit complete conflict for auditId: " + auditId
                                + ": existing status=" + existing.status()
                                + ", new status=" + completion.status());
            }

            // STARTED → 终态
            ToolAuditSnapshot updated = toSnapshot(existing, completion);
            result[0] = updated;
            return updated;
        });

        return result[0];
    }

    @Override
    public Optional<ToolAuditSnapshot> findById(String auditId) {
        if (auditId == null || auditId.isBlank()) {
            return Optional.empty();
        }
        ToolAuditSnapshot snapshot = store.get(auditId);
        return Optional.ofNullable(snapshot);
    }

    // ---- 内部方法 ----

    /**
     * 从启动记录构造快照。
     */
    private static ToolAuditSnapshot toSnapshot(ToolInvocationAuditStarted started) {
        return new ToolAuditSnapshot(
                started.auditId(),
                started.runId(),
                started.threadId(),
                started.userId(),
                started.toolCallId(),
                started.toolName(),
                started.argumentKeySummary(),
                started.authorized(),
                started.status(),
                false,           // success: false for STARTED
                null,            // errorCode: null for STARTED
                started.startedAt(),
                null,            // completedAt: null for STARTED
                null,            // durationMs: null for STARTED
                started.createdAt(),
                started.createdAt()  // updatedAt: same as createdAt for STARTED
        );
    }

    /**
     * 从已存在的 STARTED 快照和完成记录构造终态快照。
     */
    private static ToolAuditSnapshot toSnapshot(
            ToolAuditSnapshot existing,
            ToolInvocationAuditCompletion completion) {
        return new ToolAuditSnapshot(
                existing.auditId(),
                existing.runId(),
                existing.threadId(),
                existing.userId(),
                existing.toolCallId(),
                existing.toolName(),
                existing.argumentKeySummary(),
                completion.authorized(),
                completion.status(),
                completion.success(),
                completion.errorCode(),
                existing.startedAt(),
                completion.completedAt(),
                completion.durationMs(),
                existing.createdAt(),
                completion.completedAt()  // updatedAt: from completion
        );
    }

    /**
     * 判断两个快照的 START 内容是否一致（用于 start 幂等检测）。
     * <p>
     * 比较 STARTED 状态下有意义的字段：auditId、runId、threadId、userId、
     * toolCallId、toolName、argumentKeySummary、authorized、startedAt。
     */
    private static boolean isSameStartContent(
            ToolAuditSnapshot existing,
            ToolAuditSnapshot incoming) {
        return existing.auditId().equals(incoming.auditId())
                && existing.runId().equals(incoming.runId())
                && existing.threadId().equals(incoming.threadId())
                && existing.userId().equals(incoming.userId())
                && existing.toolCallId().equals(incoming.toolCallId())
                && existing.toolName().equals(incoming.toolName())
                && Objects.equals(existing.argumentKeySummary(), incoming.argumentKeySummary())
                && existing.authorized() == incoming.authorized()
                && existing.startedAt().equals(incoming.startedAt());
    }

    /**
     * 判断已终态快照与完成记录是否一致（用于 complete 幂等检测）。
     * <p>
     * 比较终态字段：status、success、authorized、errorCode、completedAt、durationMs。
     */
    private static boolean isSameTerminalContent(
            ToolAuditSnapshot existing,
            ToolInvocationAuditCompletion completion) {
        return existing.status() == completion.status()
                && existing.success() == completion.success()
                && existing.authorized() == completion.authorized()
                && Objects.equals(existing.errorCode(), completion.errorCode())
                && existing.completedAt().equals(completion.completedAt())
                && existing.durationMs() == completion.durationMs();
    }
}
