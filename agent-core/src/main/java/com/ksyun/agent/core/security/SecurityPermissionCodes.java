package com.ksyun.agent.core.security;

/**
 * 安全管理权限编码常量。
 * <p>
 * 与 {@link ToolPermissionCodes} 对应，但用于管理接口授权，
 * 不用于工具 ACL 执行链。
 * <p>
 * 管理权限校验通过 {@code PermissionAuthorizationService} 执行，
 * 不在 Controller 或拦截器中散落字符串判断。
 */
public final class SecurityPermissionCodes {

    private static final String SECURITY_PREFIX = "security:";
    private static final String READ_SUFFIX = ":read";
    private static final String WRITE_SUFFIX = ":write";

    // --- 用户管理 ---
    public static final String USER_READ = SECURITY_PREFIX + "user" + READ_SUFFIX;
    public static final String USER_WRITE = SECURITY_PREFIX + "user" + WRITE_SUFFIX;

    // --- 角色管理 ---
    public static final String ROLE_READ = SECURITY_PREFIX + "role" + READ_SUFFIX;
    public static final String ROLE_WRITE = SECURITY_PREFIX + "role" + WRITE_SUFFIX;

    // --- Session 管理 ---
    public static final String SESSION_REVOKE = SECURITY_PREFIX + "session" + ":revoke";

    private SecurityPermissionCodes() {
        // 不可实例化
    }
}
