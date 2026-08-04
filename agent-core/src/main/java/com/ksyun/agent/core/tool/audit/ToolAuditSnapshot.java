package com.ksyun.agent.core.tool.audit;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 工具调用审计不可变快照。
 * <p>
 * 返回给调用方的完整视图。不暴露参数值和完整结果。
 */
public record ToolAuditSnapshot(
        String auditId,
        String runId,
        String threadId,
        String userId,
        String toolCallId,
        String toolName,
        Set<String> argumentKeySummary,
        boolean authorized,
        ToolAuditStatus status,
        boolean success,
        String errorCode,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Instant createdAt,
        Instant updatedAt
) {
    public ToolAuditSnapshot {
        if (auditId == null || auditId.isBlank()) {
            throw new IllegalArgumentException("auditId must not be null or blank");
        }
        argumentKeySummary = argumentKeySummary == null ? Set.of() : Set.copyOf(argumentKeySummary);
    }

    /**
     * 是否处于终态（不再可变）。
     */
    public boolean isTerminal() {
        return status != ToolAuditStatus.STARTED;
    }
}
