package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.security.UserSession;

/**
 * 请求级安全上下文。
 * <p>
 * 持有当前请求关联的 UserSession 快照。
 * 每次请求由 AuthInterceptor 设置，请求结束后清除。
 * 不得保存到 ThreadLocal 之外的静态字段。
 * 不得保存 HttpServletRequest 或 HttpServletResponse。
 * <p>
 * 位于 agent-application，agent-api 和 agent-infrastructure 都可访问。
 */
public class SecurityContext {

    private static final ThreadLocal<UserSession> CURRENT = new ThreadLocal<>();

    private SecurityContext() {
    }

    /**
     * 设置当前请求的安全上下文。
     */
    public static void set(UserSession session) {
        CURRENT.set(session);
    }

    /**
     * 获取当前请求的安全上下文。
     *
     * @return 当前 UserSession，未认证时返回 null
     */
    public static UserSession get() {
        return CURRENT.get();
    }

    /**
     * 清除当前请求的安全上下文。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
