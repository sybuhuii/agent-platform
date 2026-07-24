package com.ksyun.agent.api.security;

/**
 * 认证请求属性 Key 常量。
 * <p>
 * Controller 通过 @RequestAttribute(SESSION) 读取 UserSession。
 * 不得使用随意字符串，不得放入 ThreadLocal。
 */
public final class AuthenticatedSessionAttributes {

    private AuthenticatedSessionAttributes() {
    }

    /**
     * HttpServletRequest 属性 Key，用于存放已认证的 UserSession。
     */
    public static final String SESSION = "authenticatedSession";
}
