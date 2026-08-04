package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.approval.ApprovalDecision;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.InterruptPayload;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.infrastructure.checkpoint.CheckpointPayloadCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL Checkpoint 存储实现。
 * <p>
 * 行为与 {@link InMemoryCheckpointStore} 的公开语义一致。
 * <p>
 * 持久化模型：
 * <ul>
 *   <li>{@code payload}（JSONB）：{@link CheckpointPayloadCodec#encode(AgentCheckpoint)}
 *       生成的完整版本化 payload，内含 payloadVersion、payloadKind、
 *       stateData 字段和内嵌的 pendingApproval。</li>
 *   <li>{@code payload_version} / {@code payload_kind}：冗余列，便于不解析
 *       JSON 的查询与诊断。</li>
 *   <li>{@code pending_approval}（JSONB）：独立编码的 PendingApproval，
 *       供 {@code findPendingByUserId} 的 JSONB 路径排序使用
 *       （{@code pending_approval->'payload'->>'requestedAt'}）。</li>
 * </ul>
 * <p>
 * 写操作通过 {@link TransactionTemplate} 串行：
 * <ul>
 *   <li>save 的事务内同时写入 approval_records（HITL_RECOVERY + pendingApproval 时）</li>
 *   <li>updateIfVersionMatches 的事务内同时更新 approval_records（有决策时）</li>
 * </ul>
 * <p>
 * CAS 语义：
 * <ul>
 *   <li>save：version 必须为 0。相同 checkpointId + 相同内容幂等返回；
 *       不同内容抛 CHECKPOINT_CONFLICT。先尝试 INSERT，
 *       DuplicateKeyException 时加载已有行比较内容。</li>
 *   <li>updateIfVersionMatches：WHERE version = expectedVersion，同时校验稳定身份。</li>
 *   <li>deleteIfVersionMatches：WHERE run_id = ? AND checkpoint_id = ? AND version = ?</li>
 * </ul>
 * <p>
 * 不添加 @Component，通过 @Bean 装配。
 */
public class PostgresCheckpointStore implements CheckpointStore {

    private static final Logger log =
            LoggerFactory.getLogger(PostgresCheckpointStore.class);

    // ---- SQL: agent_checkpoints ----

    private static final String INSERT_SQL =
            "INSERT INTO agent_checkpoints ("
                    + "checkpoint_id, run_id, thread_id, user_id, execution_type, purpose, "
                    + "agent_name, node_name, payload_version, payload_kind, payload, "
                    + "pending_approval, status, version, created_at, updated_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)";

    private static final String SELECT_BASE =
            "SELECT checkpoint_id, run_id, thread_id, user_id, execution_type, purpose, "
                    + "agent_name, node_name, payload_version, payload_kind, payload, "
                    + "pending_approval, status, version, created_at, updated_at "
                    + "FROM agent_checkpoints";

    private static final String LOAD_BY_RUN_ID_SQL =
            SELECT_BASE + " WHERE run_id = ? ORDER BY "
                    + "CASE WHEN purpose = 'HITL_RECOVERY' THEN 0 ELSE 1 END, "
                    + "updated_at DESC, version DESC, checkpoint_id";

    private static final String LOAD_BY_THREAD_ID_SQL =
            SELECT_BASE + " WHERE thread_id = ? ORDER BY "
                    + "updated_at DESC, version DESC, checkpoint_id LIMIT 1";

    private static final String FIND_PENDING_BY_USER_SQL =
            SELECT_BASE + " WHERE user_id = ? AND purpose = 'HITL_RECOVERY' "
                    + "AND status = 'SUSPENDED' AND pending_approval IS NOT NULL "
                    + "ORDER BY "
                    + "(pending_approval->'payload'->>'requestedAt')::timestamptz ASC NULLS LAST, "
                    + "created_at ASC";

    private static final String FIND_BY_CHECKPOINT_ID_SQL =
            SELECT_BASE + " WHERE checkpoint_id = ?";

    private static final String UPDATE_SQL =
            "UPDATE agent_checkpoints SET "
                    + "execution_type = ?, purpose = ?, agent_name = ?, node_name = ?, "
                    + "payload_version = ?, payload_kind = ?, payload = ?::jsonb, "
                    + "pending_approval = ?::jsonb, status = ?, version = ?, updated_at = ? "
                    + "WHERE checkpoint_id = ? AND version = ?";

    private static final String DELETE_BY_RUN_ID_SQL =
            "DELETE FROM agent_checkpoints WHERE run_id = ?";

    private static final String DELETE_BY_VERSION_SQL =
            "DELETE FROM agent_checkpoints "
                    + "WHERE run_id = ? AND checkpoint_id = ? AND version = ?";

    private static final String FIND_BY_THREAD_ID_SQL =
            SELECT_BASE + " WHERE thread_id = ? ORDER BY "
                    + "updated_at DESC, version DESC, checkpoint_id";

    private static final String FIND_BY_USER_THREAD_PURPOSE_SQL =
            SELECT_BASE + " WHERE user_id = ? AND thread_id = ? AND purpose = ? ORDER BY "
                    + "updated_at DESC, version DESC, checkpoint_id";

    private static final String LOAD_LATEST_BY_THREAD_SQL =
            SELECT_BASE + " WHERE user_id = ? AND thread_id = ? AND purpose = ? ORDER BY "
                    + "updated_at DESC, version DESC, checkpoint_id LIMIT 1";

    private static final String DELETE_BY_THREAD_ID_SQL =
            "DELETE FROM agent_checkpoints WHERE thread_id = ?";

    // ---- SQL: approval_records ----

    private static final String UPSERT_APPROVAL_SQL =
            "INSERT INTO approval_records ("
                    + "approval_id, run_id, thread_id, user_id, agent_name, "
                    + "operation_type, operation_name, risk_level, reason, requested_at, "
                    + "decided_by, decision_status, decided_at, decision_comment, "
                    + "created_at, updated_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (approval_id) DO UPDATE SET "
                    + "decision_status = EXCLUDED.decision_status, "
                    + "decided_by = EXCLUDED.decided_by, "
                    + "decided_at = EXCLUDED.decided_at, "
                    + "decision_comment = EXCLUDED.decision_comment, "
                    + "updated_at = EXCLUDED.updated_at";

    private static final String UPDATE_APPROVAL_DECISION_SQL =
            "UPDATE approval_records SET "
                    + "decision_status = ?, decided_by = ?, decided_at = ?, "
                    + "decision_comment = ?, updated_at = ? "
                    + "WHERE approval_id = ? AND decision_status IS NULL";

    // ---- fields ----

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CheckpointPayloadCodec codec;

    public PostgresCheckpointStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            CheckpointPayloadCodec codec) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
        this.codec = Objects.requireNonNull(codec);
    }

    // ---- CheckpointStore ----

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

        transactionTemplate.executeWithoutResult(status -> {
            try {
                insertCheckpoint(checkpoint);
                insertApprovalRecordIfNeeded(checkpoint);
            } catch (DuplicateKeyException e) {
                handleSaveDuplicateKey(checkpoint, e);
            }
        });

        log.debug("Checkpoint saved: checkpointId={}, runId={}, version={}, "
                        + "status={}, purpose={}",
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.version(),
                checkpoint.status(),
                checkpoint.purpose());
    }

    @Override
    public Optional<AgentCheckpoint> load(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }

        List<AgentCheckpoint> results = jdbcTemplate.query(
                LOAD_BY_RUN_ID_SQL,
                (rs, rowNum) -> mapRow(rs),
                runId);

        if (results.isEmpty()) {
            return Optional.empty();
        }

        // 第一条即优先级最高（HITL_RECOVERY 优先，同优先级按稳定排序）
        return Optional.of(results.get(0));
    }

    @Override
    public Optional<AgentCheckpoint> loadByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }

        AgentCheckpoint result = jdbcTemplate.query(
                LOAD_BY_THREAD_ID_SQL,
                rs -> rs.next() ? mapRow(rs) : null,
                threadId);

        return Optional.ofNullable(result);
    }

    @Override
    public Collection<AgentCheckpoint> findPendingByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }

        List<AgentCheckpoint> results = jdbcTemplate.query(
                FIND_PENDING_BY_USER_SQL,
                (rs, rowNum) -> mapRow(rs),
                userId);

        // 只保留 pendingApproval.status == PENDING 的记录
        List<AgentCheckpoint> pending = results.stream()
                .filter(cp -> cp.pendingApproval() != null
                        && cp.pendingApproval().status() == ApprovalStatus.PENDING)
                .toList();

        return Collections.unmodifiableList(pending);
    }

    @Override
    public boolean updateIfVersionMatches(
            AgentCheckpoint checkpoint,
            long expectedVersion) {
        if (checkpoint == null) {
            return false;
        }

        if (checkpoint.version() != expectedVersion + 1) {
            return false;
        }

        Boolean result = transactionTemplate.execute(status -> {
            // 先加载已有行，校验稳定身份
            AgentCheckpoint existing = loadByCheckpointId(
                    checkpoint.checkpointId());
            if (existing == null) {
                return false;
            }

            if (existing.version() != expectedVersion) {
                return false;
            }

            if (!hasSameStableIdentity(existing, checkpoint)) {
                log.warn("Checkpoint stable identity changed on update: "
                                + "checkpointId={}, runId={}",
                        checkpoint.checkpointId(),
                        checkpoint.runId());
                return false;
            }

            int affected = jdbcTemplate.update(UPDATE_SQL,
                    checkpoint.executionType().name(),
                    checkpoint.purpose().name(),
                    checkpoint.agentName(),
                    checkpoint.nodeName(),
                    codec.currentPayloadVersion(),
                    codec.currentPayloadKind(
                            checkpoint.executionType(),
                            checkpoint.purpose()),
                    codec.encode(checkpoint),
                    encodeApprovalOrNull(checkpoint.pendingApproval()),
                    checkpoint.status().name(),
                    checkpoint.version(),
                    Timestamp.from(checkpoint.updatedAt()),
                    checkpoint.checkpointId(),
                    expectedVersion);

            if (affected == 0) {
                return false;
            }

            // 如果 pendingApproval 有决策，同步更新 approval_records
            updateApprovalDecisionIfNeeded(checkpoint);

            return true;
        });

        return Boolean.TRUE.equals(result);
    }

    @Override
    public void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }

        jdbcTemplate.update(DELETE_BY_RUN_ID_SQL, runId);
    }

    @Override
    public boolean deleteIfVersionMatches(
            String runId,
            String checkpointId,
            long expectedVersion) {
        if (runId == null || runId.isBlank()
                || checkpointId == null || checkpointId.isBlank()) {
            return false;
        }

        int affected = jdbcTemplate.update(
                DELETE_BY_VERSION_SQL,
                runId, checkpointId, expectedVersion);

        return affected > 0;
    }

    @Override
    public Collection<AgentCheckpoint> findByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return List.of();
        }

        List<AgentCheckpoint> results = jdbcTemplate.query(
                FIND_BY_THREAD_ID_SQL,
                (rs, rowNum) -> mapRow(rs),
                threadId);

        return Collections.unmodifiableList(results);
    }

    @Override
    public int deleteByThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return 0;
        }

        return jdbcTemplate.update(DELETE_BY_THREAD_ID_SQL, threadId);
    }

    @Override
    public List<AgentCheckpoint> findByThreadId(
            String userId,
            String threadId,
            CheckpointPurpose purpose) {
        if (userId == null || userId.isBlank()
                || threadId == null || threadId.isBlank()
                || purpose == null) {
            return List.of();
        }

        List<AgentCheckpoint> results = jdbcTemplate.query(
                FIND_BY_USER_THREAD_PURPOSE_SQL,
                (rs, rowNum) -> mapRow(rs),
                userId.trim(),
                threadId.trim(),
                purpose.name());

        return Collections.unmodifiableList(results);
    }

    @Override
    public Optional<AgentCheckpoint> loadLatestByThreadId(
            String userId,
            String threadId,
            CheckpointPurpose purpose) {
        if (userId == null || userId.isBlank()
                || threadId == null || threadId.isBlank()
                || purpose == null) {
            return Optional.empty();
        }

        AgentCheckpoint result = jdbcTemplate.query(
                LOAD_LATEST_BY_THREAD_SQL,
                rs -> rs.next() ? mapRow(rs) : null,
                userId.trim(),
                threadId.trim(),
                purpose.name());

        return Optional.ofNullable(result);
    }

    // ---- 内部方法 ----

    private void insertCheckpoint(AgentCheckpoint checkpoint) {
        jdbcTemplate.update(INSERT_SQL,
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.threadId(),
                checkpoint.userId(),
                checkpoint.executionType().name(),
                checkpoint.purpose().name(),
                checkpoint.agentName(),
                checkpoint.nodeName(),
                codec.currentPayloadVersion(),
                codec.currentPayloadKind(
                        checkpoint.executionType(),
                        checkpoint.purpose()),
                codec.encode(checkpoint),
                encodeApprovalOrNull(checkpoint.pendingApproval()),
                checkpoint.status().name(),
                checkpoint.version(),
                Timestamp.from(checkpoint.createdAt()),
                Timestamp.from(checkpoint.updatedAt()));
    }

    /**
     * 保存 HITL_RECOVERY checkpoint 时，同时写入 approval_records。
     * <p>
     * 必须在同一个事务中，确保审批记录与 checkpoint 一致。
     */
    private void insertApprovalRecordIfNeeded(AgentCheckpoint checkpoint) {
        if (checkpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            return;
        }

        PendingApproval pa = checkpoint.pendingApproval();
        if (pa == null) {
            return;
        }

        InterruptPayload payload = pa.payload();

        // decision_status: PENDING 时为 null（数据库约定）
        String decisionStatus = pa.status() == ApprovalStatus.PENDING
                ? null
                : pa.status().name();

        String decidedBy = null;
        Timestamp decidedAt = null;
        String decisionComment = null;

        if (pa.decision() != null) {
            ApprovalDecision decision = pa.decision();
            decidedBy = decision.decidedBy();
            decidedAt = Timestamp.from(decision.decidedAt());
            decisionComment = decision.comment();
        }

        jdbcTemplate.update(UPSERT_APPROVAL_SQL,
                payload.approvalId(),
                payload.runId(),
                payload.threadId(),
                payload.userId(),
                payload.agentName(),
                payload.operationType().name(),
                payload.operationName(),
                payload.riskLevel().name(),
                payload.reason(),
                Timestamp.from(payload.requestedAt()),
                decidedBy,
                decisionStatus,
                decidedAt,
                decisionComment,
                Timestamp.from(pa.createdAt()),
                Timestamp.from(pa.updatedAt()));
    }

    /**
     * 更新时如果 pendingApproval 有决策，同步更新 approval_records。
     */
    private void updateApprovalDecisionIfNeeded(AgentCheckpoint checkpoint) {
        PendingApproval pa = checkpoint.pendingApproval();
        if (pa == null) {
            return;
        }

        if (pa.status() == ApprovalStatus.PENDING) {
            return;
        }

        ApprovalDecision decision = pa.decision();
        if (decision == null) {
            return;
        }

        int affected = jdbcTemplate.update(
                UPDATE_APPROVAL_DECISION_SQL,
                pa.status().name(),
                decision.decidedBy(),
                Timestamp.from(decision.decidedAt()),
                decision.comment(),
                Timestamp.from(pa.updatedAt()),
                pa.approvalId());

        if (affected == 0) {
            // approval_records 可能已被外部决策更新，或已存在决策
            // 使用 upsert 保证最终一致
            jdbcTemplate.update(UPSERT_APPROVAL_SQL,
                    pa.approvalId(),
                    pa.runId(),
                    pa.threadId(),
                    pa.userId(),
                    pa.payload().agentName(),
                    pa.payload().operationType().name(),
                    pa.payload().operationName(),
                    pa.payload().riskLevel().name(),
                    pa.payload().reason(),
                    Timestamp.from(pa.payload().requestedAt()),
                    decision.decidedBy(),
                    pa.status().name(),
                    Timestamp.from(decision.decidedAt()),
                    decision.comment(),
                    Timestamp.from(pa.createdAt()),
                    Timestamp.from(pa.updatedAt()));
        }
    }

    /**
     * 处理 save 时的 DuplicateKeyException。
     * <p>
     * 两种可能：
     * <ul>
     *   <li>相同 checkpointId + 相同内容 → 幂等返回</li>
     *   <li>相同 checkpointId + 不同内容 → 抛 CHECKPOINT_CONFLICT</li>
     *   <li>相同 (run_id, purpose) 但不同 checkpoint_id → 抛 CHECKPOINT_CONFLICT</li>
     * </ul>
     */
    private void handleSaveDuplicateKey(
            AgentCheckpoint checkpoint,
            DuplicateKeyException ignored) {
        // 先尝试按 checkpoint_id 加载已有行
        AgentCheckpoint existing = loadByCheckpointId(
                checkpoint.checkpointId());

        if (existing != null) {
            if (isSameContent(existing, checkpoint)) {
                log.debug("Checkpoint save idempotent: checkpointId={}, "
                                + "runId={}",
                        checkpoint.checkpointId(),
                        checkpoint.runId());
                return;
            }

            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Checkpoint already exists for checkpointId="
                            + checkpoint.checkpointId());
        }

        // (run_id, purpose) 唯一约束冲突
        throw new AgentFrameworkException(
                AgentErrorCode.CHECKPOINT_CONFLICT,
                "Checkpoint conflict on (runId, purpose): runId="
                        + checkpoint.runId()
                        + ", purpose=" + checkpoint.purpose());
    }

    private AgentCheckpoint loadByCheckpointId(String checkpointId) {
        return jdbcTemplate.query(
                FIND_BY_CHECKPOINT_ID_SQL,
                rs -> rs.next() ? mapRow(rs) : null,
                checkpointId);
    }

    /**
     * 行映射：从 ResultSet 构造完整 AgentCheckpoint。
     * <p>
     * 先用顶层列 + pending_approval 列构造一个 partial checkpoint，
     * 再调用 {@link CheckpointPayloadCodec#decode(String, AgentCheckpoint)}
     * 从 payload JSON 还原 stateData，并可能用内嵌的 pendingApproval
     * 覆盖 partial 中的值。
     * <p>
     * 注意：partial 必须通过 AgentCheckpoint 的构造函数验证
     * （SUSPENDED/RESUMING 必须有 pendingApproval），
     * 因此需要先从 pending_approval 列解码 approval。
     */
    private AgentCheckpoint mapRow(ResultSet rs) throws SQLException {
        String payloadJson = rs.getString("payload");

        // 顶层列直接读取
        String checkpointId = rs.getString("checkpoint_id");
        String runId = rs.getString("run_id");
        String threadId = rs.getString("thread_id");
        String userId = rs.getString("user_id");
        CheckpointExecutionType executionType =
                CheckpointExecutionType.valueOf(rs.getString("execution_type"));
        CheckpointPurpose purpose =
                CheckpointPurpose.valueOf(rs.getString("purpose"));
        String agentName = rs.getString("agent_name");
        String nodeName = rs.getString("node_name");
        CheckpointStatus status =
                CheckpointStatus.valueOf(rs.getString("status"));
        long version = rs.getLong("version");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        if (payloadJson == null) {
            // 无 payload 不应发生（列非空），防御性返回空 stateData
            String paJson = rs.getString("pending_approval");
            PendingApproval pa = paJson != null
                    ? codec.decodeApproval(paJson) : null;
            return new AgentCheckpoint(
                    checkpointId, runId, threadId, userId,
                    executionType, purpose, agentName, nodeName,
                    Map.of(), pa, status, version, createdAt, updatedAt);
        }

        // 从 pending_approval 列解码，满足 AgentCheckpoint 构造函数约束
        String paJson = rs.getString("pending_approval");
        PendingApproval partialApproval = paJson != null
                ? codec.decodeApproval(paJson) : null;

        // partial checkpoint：stateData 用空 Map（codec.decode 会替换）
        AgentCheckpoint partial = new AgentCheckpoint(
                checkpointId, runId, threadId, userId,
                executionType, purpose, agentName, nodeName,
                Map.of(), partialApproval, status, version, createdAt, updatedAt);

        return codec.decode(payloadJson, partial);
    }

    private String encodeApprovalOrNull(PendingApproval pendingApproval) {
        return pendingApproval != null
                ? codec.encodeApproval(pendingApproval)
                : null;
    }

    /**
     * 稳定身份校验（与 InMemoryCheckpointStore 一致）。
     */
    private boolean hasSameStableIdentity(
            AgentCheckpoint existing,
            AgentCheckpoint updated) {
        return existing.checkpointId().equals(updated.checkpointId())
                && existing.runId().equals(updated.runId())
                && existing.threadId().equals(updated.threadId())
                && existing.userId().equals(updated.userId())
                && existing.executionType() == updated.executionType()
                && existing.agentName().equals(updated.agentName())
                && existing.createdAt().equals(updated.createdAt());
    }

    /**
     * 内容等价判断（与 InMemoryCheckpointStore 一致）。
     * <p>
     * 用于幂等 save 检测。stateData 和 pendingApproval 通过
     * Objects.equals 比较（AgentCheckpoint 在构造时已做不可变深拷贝）。
     */
    private boolean isSameContent(
            AgentCheckpoint first,
            AgentCheckpoint second) {
        return first.checkpointId().equals(second.checkpointId())
                && first.runId().equals(second.runId())
                && first.threadId().equals(second.threadId())
                && first.userId().equals(second.userId())
                && first.executionType() == second.executionType()
                && first.purpose() == second.purpose()
                && first.agentName().equals(second.agentName())
                && first.nodeName().equals(second.nodeName())
                && first.status() == second.status()
                && first.version() == second.version()
                && Objects.equals(first.pendingApproval(),
                        second.pendingApproval())
                && Objects.equals(first.stateData(),
                        second.stateData())
                && first.createdAt().equals(second.createdAt())
                && first.updatedAt().equals(second.updatedAt());
    }
}
