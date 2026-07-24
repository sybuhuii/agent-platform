package com.ksyun.agent.runtime.tool.authorization;

/**
 * 工具授权决策结果，不可变。
 * <p>
 * 仅保存稳定的非敏感原因编码和所需的权限编码。
 * 不得保存完整权限集合、UserSession、密码、sessionId 或 HTTP 对象。
 *
 * @param allowed           是否允许执行
 * @param requiredPermission 当前工具所需权限编码
 * @param reasonCode        非敏感原因编码
 */
public record ToolAuthorizationDecision(
        boolean allowed,
        String requiredPermission,
        String reasonCode
) {

    /**
     * 原因编码常量。
     */
    public static final String ALLOWED_EXACT = "ALLOWED_EXACT";
    public static final String ALLOWED_WILDCARD = "ALLOWED_WILDCARD";
    public static final String MISSING_PERMISSION = "MISSING_PERMISSION";
    public static final String MISSING_CONTEXT = "MISSING_CONTEXT";

    public ToolAuthorizationDecision {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (requiredPermission == null || requiredPermission.isBlank()) {
            throw new IllegalArgumentException("requiredPermission must not be blank");
        }
    }
}
