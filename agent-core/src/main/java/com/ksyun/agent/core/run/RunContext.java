package com.ksyun.agent.core.run;

import java.util.Collections;
import java.util.Set;

/**
 * 运行上下文，不可变。
 * <p>
 * 该对象用于框架和工具，不用于直接发送给 LLM。
 *
 * @param userId      用户 ID
 * @param sessionId   会话 ID
 * @param threadId    线程 ID
 * @param runId       运行 ID
 * @param roles       角色集合，不可变
 * @param permissions 权限集合，不可变
 */
public record RunContext(
        String userId,
        String sessionId,
        String threadId,
        String runId,
        Set<String> roles,
        Set<String> permissions
) {

    public RunContext {
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(roles);
        permissions = permissions == null ? Set.of() : Collections.unmodifiableSet(permissions);
    }

    /**
     * 判断是否包含指定角色。
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * 判断是否包含指定权限。
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
