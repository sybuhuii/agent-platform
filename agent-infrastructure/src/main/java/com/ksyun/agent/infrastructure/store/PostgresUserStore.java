package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserAccount;
import com.ksyun.agent.core.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL 用户存储实现。
 * <p>
 * 行为与 {@link InMemoryUserStore} 的公开语义一致：
 * - save 是创建语义，重复 username 返回 USER_ALREADY_EXISTS。
 * - 重复 userId 返回 INVALID_ARGUMENT。
 * - update 时用户必须存在，否则 USER_NOT_FOUND。
 * - userId 和 username 创建后不可修改。
 * - existsByUsername 使用与领域模型一致的 trim、大小写敏感语义。
 * <p>
 * 用户本体和 user_roles 的写入在同一事务中完成。
 * 数据库唯一约束是并发边界，不只依赖 exists 后 insert。
 * 不添加 @Component 或 @Repository，通过 @Bean 装配。
 */
public class PostgresUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresUserStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgresUserStore(JdbcTemplate jdbcTemplate,
                             PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void save(UserAccount user) {
        Objects.requireNonNull(user, "UserAccount must not be null");

        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "INSERT INTO users (user_id, username, credential_hash, enabled) VALUES (?, ?, ?, ?)",
                        user.userId(), user.username(), user.credentialHash(), user.enabled());
                batchInsertRoles(user.userId(), user.roleNames());
            });
            log.info("User saved: userId={}, username={}", user.userId(), user.username());
        } catch (DuplicateKeyException e) {
            // 区分是 username 还是 userId 冲突
            if (existsByUsername(user.username())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.USER_ALREADY_EXISTS,
                        "Username is already in use"
                );
            }
            // userId 冲突
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "UserId already exists"
            );
        }
    }

    @Override
    public void update(UserAccount user) {
        Objects.requireNonNull(user, "UserAccount must not be null");

        UserAccount existing = findById(user.userId())
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.USER_NOT_FOUND,
                        "User not found for update: " + user.userId()
                ));

        // userId 和 username 创建后不可修改
        if (!existing.username().equals(user.username())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Username cannot be changed after creation"
            );
        }

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE users SET credential_hash = ?, enabled = ? WHERE user_id = ?",
                    user.credentialHash(), user.enabled(), user.userId());
            // 原子替换角色集合
            jdbcTemplate.update(
                    "DELETE FROM user_roles WHERE user_id = ?",
                    user.userId());
            batchInsertRoles(user.userId(), user.roleNames());
        });

        log.info("User updated: userId={}", user.userId());
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        UserAccount account = jdbcTemplate.query(
                "SELECT user_id, username, credential_hash, enabled FROM users WHERE user_id = ?",
                rs -> rs.next() ? mapRow(rs) : null,
                userId);
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(withRoles(account));
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = UserAccount.normalizeUsername(username);
        UserAccount account = jdbcTemplate.query(
                "SELECT user_id, username, credential_hash, enabled FROM users WHERE username = ?",
                rs -> rs.next() ? mapRow(rs) : null,
                normalized);
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(withRoles(account));
    }

    @Override
    public Collection<UserAccount> list() {
        List<UserAccount> users = jdbcTemplate.query(
                "SELECT user_id, username, credential_hash, enabled FROM users ORDER BY user_id",
                (rs, rowNum) -> mapRow(rs));

        if (users.isEmpty()) {
            return List.of();
        }

        // 批量查询角色避免 N+1
        List<String> userIds = users.stream().map(UserAccount::userId).toList();
        Map<String, Set<String>> rolesMap = batchQueryRoles(userIds);

        List<UserAccount> result = new ArrayList<>();
        for (UserAccount u : users) {
            Set<String> roles = rolesMap.getOrDefault(u.userId(), Set.of());
            result.add(new UserAccount(
                    u.userId(), u.username(), u.credentialHash(), roles, u.enabled()));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean existsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String normalized = UserAccount.normalizeUsername(username);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, normalized);
        return count != null && count > 0;
    }

    // ---- 内部方法 ----

    private UserAccount mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserAccount(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("credential_hash"),
                Set.of(),
                rs.getBoolean("enabled"));
    }

    private UserAccount withRoles(UserAccount account) {
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role_name FROM user_roles WHERE user_id = ?",
                String.class, account.userId());
        Set<String> roleSet = Set.copyOf(roles);
        return new UserAccount(
                account.userId(), account.username(),
                account.credentialHash(), roleSet, account.enabled());
    }

    private void batchInsertRoles(String userId, Set<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO user_roles (user_id, role_name) VALUES (?, ?)",
                roleNames.stream()
                        .map(role -> new Object[]{userId, role})
                        .toList());
    }

    private Map<String, Set<String>> batchQueryRoles(List<String> userIds) {
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        String sql = "SELECT user_id, role_name FROM user_roles WHERE user_id IN ("
                + placeholders + ")";

        Map<String, Set<String>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String uid = rs.getString("user_id");
            String role = rs.getString("role_name");
            result.computeIfAbsent(uid, k -> new HashSet<>()).add(role);
        }, userIds.toArray());

        Map<String, Set<String>> immutable = new HashMap<>();
        result.forEach((k, v) -> immutable.put(k, Set.copyOf(v)));
        return immutable;
    }
}
