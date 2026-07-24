package com.ksyun.agent.runtime.security;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.security.RolePermissionResolver;
import com.ksyun.agent.core.store.RoleStore;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 默认角色权限解析器实现。
 * <p>
 * 根据角色名称查找 RoleDefinition，合并所有 permissionCodes，去重后返回不可变 Set。
 * 未知角色明确抛出 AgentFrameworkException，不静默忽略。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultRolePermissionResolver implements RolePermissionResolver {

    private final RoleStore roleStore;

    public DefaultRolePermissionResolver(RoleStore roleStore) {
        this.roleStore = Objects.requireNonNull(roleStore, "roleStore must not be null");
    }

    @Override
    public Set<String> resolvePermissions(Set<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "roleNames must not be empty"
            );
        }

        Set<String> allPermissions = new HashSet<>();
        for (String roleName : roleNames) {
            RoleDefinition role = roleStore.find(roleName)
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.ROLE_NOT_FOUND,
                            "Unknown role: " + roleName
                    ));
            allPermissions.addAll(role.permissionCodes());
        }
        return Collections.unmodifiableSet(allPermissions);
    }
}
