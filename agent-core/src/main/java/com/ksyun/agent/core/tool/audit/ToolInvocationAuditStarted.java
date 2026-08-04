package com.ksyun.agent.core.tool.audit;

import java.time.Instant;
import java.util.Set;

/**
 * 工具调用审计启动记录，不可变。
 * <p>
 * 在真实工具执行前写入，确保"无审计不执行"。
 * <p>
 * 禁止记录：参数值、完整 arguments、完整 ToolResult、sessionId、roles、permissions、
 * API Key、密码、credentialHash、完整模型消息、Checkpoint payload。
 * <p>
 * 可记录安全字段：auditId、runId、threadId、userId、toolCallId、toolName、
 * 脱敏参数键名/数量、authorized、success、errorCode、状态、时间。
 *
 * @param auditId             审计唯一标识
 * @param runId               运行 ID
 * @param threadId            线程 ID
 * @param userId              用户 ID
 * @param toolCallId          工具调用 ID（恢复重试中可能重复）
 * @param toolName            工具名称
 * @param argumentKeySummary  安全参数名摘要（键名集合或数量，不含值）
 * @param authorized          是否经过 ACL 授权
 * @param status              审计状态（STARTED）
 * @param startedAt           开始时间
 * @param createdAt           记录创建时间
 */
public record ToolInvocationAuditStarted(
        String auditId,
        String runId,
        String threadId,
        String userId,
        String toolCallId,
        String toolName,
        Set<String> argumentKeySummary,
        boolean authorized,
        ToolAuditStatus status,
        Instant startedAt,
        Instant createdAt
) {
    public ToolInvocationAuditStarted {
        if (auditId == null || auditId.isBlank()) {
            throw new IllegalArgumentException("auditId must not be null or blank");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be null or blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be null or blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be null or blank");
        }
        if (status != ToolAuditStatus.STARTED) {
            throw new IllegalArgumentException("status must be STARTED, got " + status);
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        argumentKeySummary = argumentKeySummary == null ? Set.of() : Set.copyOf(argumentKeySummary);
    }
}
