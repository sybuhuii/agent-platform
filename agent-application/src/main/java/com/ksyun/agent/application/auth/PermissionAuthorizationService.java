package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理权限校验服务。
 * <p>
 * 用于管理 API 的授权判断，只读取已验证 {@link UserSession} 的 permissions。
 * <p>
 * 工具 ACL 继续使用 {@code ToolPermissionEvaluator}，不得将两者混入一套工具执行逻辑。
 * <p>
 * 纯 Java 实现，不依赖 Spring、HttpServletRequest 或 ThreadLocal。
 * 保持无状态和线程安全。
 */
public class PermissionAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuthorizationService.class);

    /**
     * 校验当前用户是否拥有指定权限，权限不足时抛出 PERMISSION_DENIED。
     *
     * @param session         已验证的用户会话
     * @param permissionCode  所需权限编码
     * @throws AgentFrameworkException PERMISSION_DENIED 当权限不足
     */
    public void requirePermission(UserSession session, String permissionCode) {
        if (!hasPermission(session, permissionCode)) {
            log.warn("Management permission denied: userId={}, requiredPermission={}",
                    session != null ? session.userId() : "unknown",
                    permissionCode);
            throw new AgentFrameworkException(AgentErrorCode.PERMISSION_DENIED,
                    "Permission denied: requires '" + permissionCode + "'");
        }
    }

    /**
     * 检查当前用户是否拥有指定权限。
     *
     * @param session         已验证的用户会话
     * @param permissionCode  所需权限编码
     * @return true 如果拥有该权限
     */
    public boolean hasPermission(UserSession session, String permissionCode) {
        if (session == null) {
            return false;
        }
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        return session.permissions().contains(permissionCode);
    }
}
