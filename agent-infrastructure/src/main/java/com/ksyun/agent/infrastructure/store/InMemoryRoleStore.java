package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.store.RoleStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存角色存储实现。
 * <p>
 * 使用 ConcurrentHashMap，roleName 唯一。
 * 重复角色注册明确失败，不静默覆盖。
 * 不得添加 @Component。不得内置具体角色。不得访问 UserStore。
 */
public class InMemoryRoleStore implements RoleStore {

    private final ConcurrentHashMap<String, RoleDefinition> roles = new ConcurrentHashMap<>();

    @Override
    public void save(RoleDefinition role) {
        if (role == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "RoleDefinition must not be null"
            );
        }
        RoleDefinition existing = roles.putIfAbsent(role.roleName(), role);
        if (existing != null) {
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
        RoleDefinition existing = roles.get(role.roleName());
        if (existing == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.ROLE_NOT_FOUND,
                    "Role not found for update: " + role.roleName()
            );
        }
        // 原子替换，roleName 不可修改
        roles.put(role.roleName(), role);
    }

    @Override
    public Optional<RoleDefinition> find(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(roles.get(roleName));
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
        return Collections.unmodifiableList(new ArrayList<>(roles.values()));
    }
}
