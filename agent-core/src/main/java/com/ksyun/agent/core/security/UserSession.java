package com.ksyun.agent.core.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * 用户会话，不可变。
 * <p>
 * Session 创建时保存角色和权限快照。
 * 不得保存明文密码或 credentialHash。
 * 不得保存 HttpSession、ServletRequest 或 Spring Security 对象。
 * 不得保存 ModelClient、AgentState 或消息历史。
 * 不得允许调用方修改已有 Session。
 *
 * @param sessionId  会话 ID，不能为空
 * @param userId     用户 ID，不能为空
 * @param username   用户名，不能为空
 * @param roles      角色集合，不可变
 * @param permissions 权限集合，不可变
 * @param createdAt  创建时间
 * @param expiresAt  过期时间，可为 null 表示永不过期
 */
public record UserSession(
        String sessionId,
        String userId,
        String username,
        Set<String> roles,
        Set<String> permissions,
        Instant createdAt,
        Instant expiresAt
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public UserSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(roles);
        permissions = permissions == null ? Set.of() : Collections.unmodifiableSet(permissions);
    }

    /**
     * 判断会话是否已过期。
     *
     * @param now 当前时间，由调用方传入，不使用 Instant.now()
     * @return 如果已过期返回 true
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
