package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.store.RoleStore;
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
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL 角色存储实现。
 * <p>
 * 行为与 {@link InMemoryRoleStore} 的公开语义一致：
 * - save 是创建语义，重复角色不得静默覆盖。
 * - update 只更新已存在角色，不存在返回 ROLE_NOT_FOUND。
 * - roleName 创建后不可修改。
 * - find 查询不到返回 Optional.empty()。
 * - list 返回不可变快照，避免 N+1 查询。
 * <p>
 * 角色本体和 role_permissions 的写入在同一事务中完成。
 * 不添加 @Component 或 @Repository，通过 @Bean 装配。
 */
public class PostgresRoleStore implements RoleStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresRoleStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgresRoleStore(JdbcTemplate jdbcTemplate,
                             PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void save(RoleDefinition role) {
        if (role == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "RoleDefinition must not be null"
            );
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "INSERT INTO roles (role_name, description) VALUES (?, ?)",
                        role.roleName(), role.description());
                batchInsertPermissions(role.roleName(), role.permissionCodes());
            });
            log.info("Role saved: {}", role.roleName());
        } catch (DuplicateKeyException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Role already registered with name: " + role.roleName()
            );
        }
    }

    @Override
    public void update(RoleDefinition role) {
        if (role == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "RoleDefinition must not be null"
            );
        }

        Boolean exists = transactionTemplate.execute(status -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM roles WHERE role_name = ?",
                    Integer.class, role.roleName());
            return count != null && count > 0;
        });

        if (!Boolean.TRUE.equals(exists)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.ROLE_NOT_FOUND,
                    "Role not found for update: " + role.roleName()
            );
        }

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE roles SET description = ? WHERE role_name = ?",
                    role.description(), role.roleName());
            // 原子替换权限集合
            jdbcTemplate.update(
                    "DELETE FROM role_permissions WHERE role_name = ?",
                    role.roleName());
            batchInsertPermissions(role.roleName(), role.permissionCodes());
        });

        log.info("Role updated: {}", role.roleName());
    }

    @Override
    public Optional<RoleDefinition> find(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Optional.empty();
        }

        String description = jdbcTemplate.query(
                "SELECT description FROM roles WHERE role_name = ?",
                rs -> rs.next() ? rs.getString("description") : null,
                roleName);

        if (description == null && !roleExists(roleName)) {
            return Optional.empty();
        }

        Set<String> permissions = queryPermissions(roleName);
        return Optional.of(new RoleDefinition(roleName, description, permissions));
    }

    @Override
    public RoleDefinition getRequired(String roleName) {
        return find(roleName).orElseThrow(() ->
                new AgentFrameworkException(
                        AgentErrorCode.ROLE_NOT_FOUND,
                        "Role not found: " + roleName
                )
        );
    }

    @Override
    public Collection<RoleDefinition> list() {
        // 一次性查询所有角色和权限，避免 N+1
        List<String> roleNames = jdbcTemplate.queryForList(
                "SELECT role_name FROM roles ORDER BY role_name",
                String.class);

        if (roleNames.isEmpty()) {
            return List.of();
        }

        // 批量查询所有角色的权限
        Map<String, Set<String>> permissionsMap = batchQueryPermissions(roleNames);

        // 查询描述
        List<RoleDefinition> result = new ArrayList<>();
        for (String rn : roleNames) {
            String desc = jdbcTemplate.query(
                    "SELECT description FROM roles WHERE role_name = ?",
                    rs -> rs.next() ? rs.getString("description") : null,
                    rn);
            result.add(new RoleDefinition(rn, desc, permissionsMap.getOrDefault(rn, Set.of())));
        }

        return Collections.unmodifiableList(result);
    }

    // ---- 内部方法 ----

    private boolean roleExists(String roleName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE role_name = ?",
                Integer.class, roleName);
        return count != null && count > 0;
    }

    private Set<String> queryPermissions(String roleName) {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT permission_code FROM role_permissions WHERE role_name = ?",
                String.class, roleName);
        return Set.copyOf(codes);
    }

    private void batchInsertPermissions(String roleName, Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO role_permissions (role_name, permission_code) VALUES (?, ?)",
                permissionCodes.stream()
                        .map(code -> new Object[]{roleName, code})
                        .toList());
    }

    private Map<String, Set<String>> batchQueryPermissions(List<String> roleNames) {
        // 使用 IN 子句批量查询
        String placeholders = String.join(",", Collections.nCopies(roleNames.size(), "?"));
        String sql = "SELECT role_name, permission_code FROM role_permissions WHERE role_name IN ("
                + placeholders + ")";

        Map<String, Set<String>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String rn = rs.getString("role_name");
            String code = rs.getString("permission_code");
            result.computeIfAbsent(rn, k -> new HashSet<>()).add(code);
        }, roleNames.toArray());

        // 转为不可变
        Map<String, Set<String>> immutable = new HashMap<>();
        result.forEach((k, v) -> immutable.put(k, Set.copyOf(v)));
        return immutable;
    }
}
