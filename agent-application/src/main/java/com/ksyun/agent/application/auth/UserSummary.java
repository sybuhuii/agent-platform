package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.security.UserAccount;

import java.util.Set;

/**
 * 用户摘要信息，用于管理 API 响应。
 * <p>
 * 不包含 credentialHash。
 * 使用不可变 record 类型。
 *
 * @param userId   用户 ID
 * @param username 用户名
 * @param roleNames 角色名称集合
 * @param enabled  是否启用
 */
public record UserSummary(
        String userId,
        String username,
        Set<String> roleNames,
        boolean enabled
) {
    /**
     * 从 UserAccount 创建摘要。
     */
    public static UserSummary from(UserAccount account) {
        return new UserSummary(
                account.userId(),
                account.username(),
                account.roleNames(),
                account.enabled()
        );
    }
}
