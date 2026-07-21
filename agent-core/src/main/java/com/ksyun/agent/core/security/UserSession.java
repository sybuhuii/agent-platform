package com.ksyun.agent.core.security;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * 用户会话。
 *
 * @param sessionId  会话 ID
 * @param userId     用户 ID
 * @param roles      角色集合，不可变
 * @param permissions 权限集合，不可变
 * @param createdAt  创建时间
 * @param expiresAt  过期时间
 */
public record UserSession(
        String sessionId,
        String userId,
        Set<String> roles,
        Set<String> permissions,
        Instant createdAt,
        Instant expiresAt
) {

    public UserSession {
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(roles);
        permissions = permissions == null ? Set.of() : Collections.unmodifiableSet(permissions);
    }

    /**
     * 判断会话是否已过期。
     *
     * @param now 当前时间
     * @return 如果已过期返回 true
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
