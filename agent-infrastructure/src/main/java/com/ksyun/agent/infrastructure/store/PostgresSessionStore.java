package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL Session 存储实现。
 * <p>
 * 行为与 {@link InMemorySessionStore} 的公开语义一致：
 * - Store 不生成 sessionId，不自动续期。
 * - save 为创建语义；相同 sessionId 且完全相同内容幂等；
 *   相同 sessionId 但内容不同明确失败。
 * - findBySessionId 空白参数返回 Optional.empty()。
 * - delete 幂等。
 * - findByUserId 在 SQL 中严格按 user_id 过滤。
 * <p>
 * roles/permissions 使用 PostgreSQL text[] 并做明确 JDBC 映射。
 * Instant 使用 timestamptz 和 JDBC 时区安全映射。
 * 不在日志中记录 sessionId。不添加 @Component。
 */
public class PostgresSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresSessionStore.class);

    private static final String INSERT_SQL =
            "INSERT INTO user_sessions (session_id, user_id, username, roles, permissions, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public PostgresSessionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(UserSession session) {
        if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Session and sessionId must not be null or blank"
            );
        }

        UserSession existing = findBySessionIdInternal(session.sessionId());
        if (existing != null) {
            if (existing.equals(session)) {
                // 相同内容幂等
                return;
            }
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Session already exists with different content"
            );
        }

        try {
            // 使用 ConnectionCallback 创建连接绑定的 Array 并在同一连接执行插入
            jdbcTemplate.execute((Connection con) -> {
                Array rolesArray = con.createArrayOf("text", toArray(session.roles()));
                Array permissionsArray = con.createArrayOf("text", toArray(session.permissions()));
                try (PreparedStatement ps = con.prepareStatement(INSERT_SQL)) {
                    ps.setString(1, session.sessionId());
                    ps.setString(2, session.userId());
                    ps.setString(3, session.username());
                    ps.setArray(4, rolesArray);
                    ps.setArray(5, permissionsArray);
                    ps.setTimestamp(6, Timestamp.from(session.createdAt()));
                    ps.setTimestamp(7, session.expiresAt() == null ? null
                            : Timestamp.from(session.expiresAt()));
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发：检查后插入竞争，重新读取确认是否内容一致
            UserSession concurrent = findBySessionIdInternal(session.sessionId());
            if (concurrent != null && concurrent.equals(session)) {
                return;
            }
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Session already exists with different content"
            );
        }
    }

    @Override
    public Optional<UserSession> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(findBySessionIdInternal(sessionId));
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM user_sessions WHERE session_id = ?", sessionId);
    }

    @Override
    public Collection<UserSession> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<UserSession> sessions = jdbcTemplate.query(
                "SELECT session_id, user_id, username, roles, permissions, created_at, expires_at "
                        + "FROM user_sessions WHERE user_id = ?",
                (rs, rowNum) -> mapRow(rs),
                userId);
        return Collections.unmodifiableList(sessions);
    }

    // ---- 内部方法 ----

    private UserSession findBySessionIdInternal(String sessionId) {
        return jdbcTemplate.query(
                "SELECT session_id, user_id, username, roles, permissions, created_at, expires_at "
                        + "FROM user_sessions WHERE session_id = ?",
                rs -> rs.next() ? mapRow(rs) : null,
                sessionId);
    }

    private UserSession mapRow(ResultSet rs) throws SQLException {
        Set<String> roles = toStringSet(rs.getArray("roles"));
        Set<String> permissions = toStringSet(rs.getArray("permissions"));
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp expiresTs = rs.getTimestamp("expires_at");
        Instant expiresAt = expiresTs == null ? null : expiresTs.toInstant();

        return new UserSession(
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("username"),
                roles,
                permissions,
                createdAt,
                expiresAt);
    }

    private Set<String> toStringSet(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        Object[] elements = (Object[]) array.getArray();
        if (elements.length == 0) {
            return Set.of();
        }
        Set<String> result = new HashSet<>(elements.length);
        for (Object element : elements) {
            if (element != null) {
                result.add(element.toString());
            }
        }
        return Set.copyOf(result);
    }

    private String[] toArray(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return new String[0];
        }
        return values.toArray(new String[0]);
    }
}
