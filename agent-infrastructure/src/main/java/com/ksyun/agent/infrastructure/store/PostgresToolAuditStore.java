package com.ksyun.agent.infrastructure.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.store.ToolAuditStore;
import com.ksyun.agent.core.tool.audit.ToolAuditSnapshot;
import com.ksyun.agent.core.tool.audit.ToolAuditStatus;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditCompletion;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL 工具调用审计存储实现。
 * <p>
 * 行为与 {@link InMemoryToolAuditStore} 的公开语义一致：
 * <ul>
 *   <li>{@link #start} 创建语义：相同 auditId 相同内容幂等，不同内容冲突。</li>
 *   <li>{@link #complete} 只允许 STARTED → 终态。相同终态重复提交幂等；不同终态冲突。</li>
 *   <li>返回不可变快照。</li>
 * </ul>
 * <p>
 * argument_key_summary 存储为 JSONB 数组：{@code ["key1", "key2", ...]}。
 * <p>
 * 不添加 @Component，通过 @Bean 装配。
 */
public class PostgresToolAuditStore implements ToolAuditStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresToolAuditStore.class);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final String INSERT_SQL =
            "INSERT INTO tool_invocation_audits ("
                    + "audit_id, run_id, thread_id, user_id, tool_call_id, tool_name, "
                    + "argument_key_summary, authorized, status, success, error_code, "
                    + "started_at, completed_at, duration_ms, created_at, updated_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'STARTED', NULL, NULL, ?, NULL, NULL, ?, ?) "
                    + "ON CONFLICT (audit_id) DO NOTHING";

    private static final String SELECT_BY_AUDIT_ID_SQL =
            "SELECT audit_id, run_id, thread_id, user_id, tool_call_id, tool_name, "
                    + "argument_key_summary, authorized, status, success, error_code, "
                    + "started_at, completed_at, duration_ms, created_at, updated_at "
                    + "FROM tool_invocation_audits WHERE audit_id = ?";

    private static final String UPDATE_TO_TERMINAL_SQL =
            "UPDATE tool_invocation_audits SET "
                    + "status = ?, success = ?, error_code = ?, "
                    + "authorized = ?, completed_at = ?, duration_ms = ?, updated_at = ? "
                    + "WHERE audit_id = ? AND status = 'STARTED'";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public PostgresToolAuditStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ToolAuditSnapshot start(ToolInvocationAuditStarted started) {
        Objects.requireNonNull(started, "started must not be null");

        String argumentKeysJson = serializeArgumentKeys(started.argumentKeySummary());

        int affected = jdbcTemplate.update(INSERT_SQL,
                started.auditId(),
                started.runId(),
                started.threadId(),
                started.userId(),
                started.toolCallId(),
                started.toolName(),
                argumentKeysJson,
                started.authorized(),
                Timestamp.from(started.startedAt()),
                Timestamp.from(started.createdAt()),
                Timestamp.from(started.createdAt()));

        if (affected > 0) {
            // INSERT 成功：加载并返回
            return loadSnapshot(started.auditId());
        }

        // 0 rows affected: audit_id 冲突 → 检查幂等或冲突
        ToolAuditSnapshot existing = loadSnapshot(started.auditId());
        if (existing == null) {
            // 并发删除的极端情况
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Tool audit disappeared after insert conflict for auditId: "
                            + started.auditId());
        }

        if (isSameStartContent(existing, started)) {
            // 相同内容 → 幂等返回
            return existing;
        }

        // 不同内容 → 冲突
        throw new AgentFrameworkException(
                AgentErrorCode.CHECKPOINT_CONFLICT,
                "Tool audit start conflict for auditId: " + started.auditId());
    }

    @Override
    public ToolAuditSnapshot complete(ToolInvocationAuditCompletion completion) {
        Objects.requireNonNull(completion, "completion must not be null");

        return transactionTemplate.execute(status -> {
            ToolAuditSnapshot existing = loadSnapshot(completion.auditId());
            if (existing == null) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Tool audit not found for auditId: " + completion.auditId());
            }

            // 已是终态
            if (existing.isTerminal()) {
                // 相同终态 + 相同内容 → 幂等返回
                if (isSameTerminalContent(existing, completion)) {
                    return existing;
                }
                // 不同终态 → 冲突
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Tool audit complete conflict for auditId: " + completion.auditId()
                                + ": existing status=" + existing.status()
                                + ", new status=" + completion.status());
            }

            // STARTED → 终态
            int affected = jdbcTemplate.update(UPDATE_TO_TERMINAL_SQL,
                    completion.status().name(),
                    completion.success(),
                    completion.errorCode(),
                    completion.authorized(),
                    Timestamp.from(completion.completedAt()),
                    completion.durationMs(),
                    Timestamp.from(completion.completedAt()),
                    completion.auditId());

            if (affected == 0) {
                // 并发 complete 已将 STARTED 转为终态
                ToolAuditSnapshot now = loadSnapshot(completion.auditId());
                if (now != null && now.isTerminal()
                        && isSameTerminalContent(now, completion)) {
                    return now;
                }
                throw new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Tool audit complete conflict for auditId: " + completion.auditId()
                                + ": concurrent status change");
            }

            return loadSnapshot(completion.auditId());
        });
    }

    @Override
    public Optional<ToolAuditSnapshot> findById(String auditId) {
        if (auditId == null || auditId.isBlank()) {
            return Optional.empty();
        }
        ToolAuditSnapshot snapshot = loadSnapshot(auditId);
        return Optional.ofNullable(snapshot);
    }

    // ---- 内部方法 ----

    private ToolAuditSnapshot loadSnapshot(String auditId) {
        return jdbcTemplate.query(SELECT_BY_AUDIT_ID_SQL,
                rs -> rs.next() ? mapRow(rs) : null,
                auditId);
    }

    private ToolAuditSnapshot mapRow(ResultSet rs) throws SQLException {
        Set<String> argumentKeys = deserializeArgumentKeys(
                rs.getString("argument_key_summary"));

        Boolean successObj = rs.getObject("success", Boolean.class);
        boolean success = successObj != null && successObj;

        String errorCode = rs.getString("error_code");

        Timestamp completedAtTs = rs.getTimestamp("completed_at");
        Instant completedAt = completedAtTs != null ? completedAtTs.toInstant() : null;

        Long durationMs = rs.getObject("duration_ms", Long.class);

        return new ToolAuditSnapshot(
                rs.getString("audit_id"),
                rs.getString("run_id"),
                rs.getString("thread_id"),
                rs.getString("user_id"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                argumentKeys,
                rs.getBoolean("authorized"),
                ToolAuditStatus.valueOf(rs.getString("status")),
                success,
                errorCode,
                rs.getTimestamp("started_at").toInstant(),
                completedAt,
                durationMs,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String serializeArgumentKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(List.copyOf(keys));
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to serialize argument key summary", e);
        }
    }

    private Set<String> deserializeArgumentKeys(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST_TYPE);
            return Set.copyOf(new LinkedHashSet<>(list));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize argument_key_summary: {}", json, e);
            return Set.of();
        }
    }

    /**
     * 判断已存在快照与启动记录的 START 内容是否一致（用于 start 幂等检测）。
     */
    private static boolean isSameStartContent(
            ToolAuditSnapshot existing,
            ToolInvocationAuditStarted started) {
        return existing.auditId().equals(started.auditId())
                && existing.runId().equals(started.runId())
                && existing.threadId().equals(started.threadId())
                && existing.userId().equals(started.userId())
                && existing.toolCallId().equals(started.toolCallId())
                && existing.toolName().equals(started.toolName())
                && Objects.equals(existing.argumentKeySummary(), started.argumentKeySummary())
                && existing.authorized() == started.authorized()
                && existing.startedAt().equals(started.startedAt());
    }

    /**
     * 判断已终态快照与完成记录是否一致（用于 complete 幂等检测）。
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
