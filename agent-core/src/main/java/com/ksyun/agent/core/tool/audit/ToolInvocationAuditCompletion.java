package com.ksyun.agent.core.tool.audit;

import java.time.Instant;

/**
 * 工具调用审计终态完成记录，不可变。
 * <p>
 * 只允许从 STARTED 原子转换到终态（SUCCEEDED/FAILED/SUSPENDED/EXCEPTION）。
 * 重复相同终态幂等；不同终态冲突。
 * <p>
 * completed 状态必须有 completedAt 和非负 durationMs。
 * STARTED 允许 completedAt 和 durationMs 为空。
 * <p>
 * 重要失败策略：真实工具可能已产生外部副作用，终态审计更新失败时
 * 不把成功工具伪装成失败并诱导重试。
 * 保留 STARTED 记录供后续识别不完整审计。
 *
 * @param auditId      审计唯一标识（与 STARTED 记录相同）
 * @param status       终态（SUCCEEDED/FAILED/SUSPENDED/EXCEPTION）
 * @param success      工具是否成功执行
 * @param authorized   是否经过 ACL 授权
 * @param errorCode    错误码（nullable，成功时为空）
 * @param completedAt  完成时间
 * @param durationMs   执行时长毫秒（非负）
 */
public record ToolInvocationAuditCompletion(
        String auditId,
        ToolAuditStatus status,
        boolean success,
        boolean authorized,
        String errorCode,
        Instant completedAt,
        long durationMs
) {
    public ToolInvocationAuditCompletion {
        if (auditId == null || auditId.isBlank()) {
            throw new IllegalArgumentException("auditId must not be null or blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == ToolAuditStatus.STARTED) {
            throw new IllegalArgumentException("status must be terminal, got STARTED");
        }
        if (completedAt == null) {
            throw new IllegalArgumentException("completedAt must not be null for terminal status");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative, got " + durationMs);
        }
    }
}
