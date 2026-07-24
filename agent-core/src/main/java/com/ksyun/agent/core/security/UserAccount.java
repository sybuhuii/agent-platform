package com.ksyun.agent.core.security;

import java.io.Serializable;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * 用户账户，不可变。
 * <p>
 * 不保存明文密码。用户名标准化规则：trim 后保持原大小写。
 * 整个系统统一采用此规则，各 Store 不得自行决定。
 * 不得包含 HttpServletRequest、Spring Security Authentication、JWT 类型。
 * 不得包含 SessionStore 等服务对象。
 * 不得包含用户运行中的消息或 Agent State。
 *
 * @param userId         用户 ID，不能为空
 * @param username       用户名，不能为空，trim 后保持原大小写
 * @param credentialHash 密码哈希，不能为空，不得保存明文密码
 * @param roleNames      角色名称集合，不可变，不能为空，不得包含空字符串
 * @param enabled        用户是否允许登录
 */
public record UserAccount(
        String userId,
        String username,
        String credentialHash,
        Set<String> roleNames,
        boolean enabled
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** 用户名标准化规则：trim 后保持原大小写。整个系统只能采用一种规则。 */
    public static String normalizeUsername(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("username must not be null");
        }
        return raw.trim();
    }

    public UserAccount {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        Objects.requireNonNull(credentialHash, "credentialHash must not be null");
        if (credentialHash.isBlank()) {
            throw new IllegalArgumentException("credentialHash must not be blank");
        }
        if (roleNames == null || roleNames.isEmpty()) {
            throw new IllegalArgumentException("roleNames must not be empty");
        }
        for (String roleName : roleNames) {
            if (roleName == null || roleName.isBlank()) {
                throw new IllegalArgumentException("roleNames must not contain blank strings");
            }
        }
        userId = userId.trim();
        username = normalizedUsername;
        credentialHash = credentialHash.trim();
        roleNames = Collections.unmodifiableSet(roleNames);
    }
}
