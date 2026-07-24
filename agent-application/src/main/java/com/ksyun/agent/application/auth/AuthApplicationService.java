package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.CredentialHasher;
import com.ksyun.agent.core.security.RolePermissionResolver;
import com.ksyun.agent.core.security.SessionIdGenerator;
import com.ksyun.agent.core.security.UserAccount;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.SessionStore;
import com.ksyun.agent.core.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;

/**
 * 认证应用服务，纯 Java 实现。
 * <p>
 * 不添加 Spring 注解。保持无状态和线程安全。
 * 不保存 HttpServletRequest、HttpServletResponse 或 Spring Security Authentication。
 * 不在日志中记录明文密码。
 * 不在 AuthApplicationService 中校验单个权限。
 */
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    private final UserStore userStore;
    private final SessionStore sessionStore;
    private final CredentialHasher credentialHasher;
    private final SessionIdGenerator sessionIdGenerator;
    private final RolePermissionResolver rolePermissionResolver;
    private final SessionValidationService sessionValidationService;
    private final SessionTtlConfig sessionTtlConfig;

    public AuthApplicationService(UserStore userStore,
                                   SessionStore sessionStore,
                                   CredentialHasher credentialHasher,
                                   SessionIdGenerator sessionIdGenerator,
                                   RolePermissionResolver rolePermissionResolver,
                                   SessionValidationService sessionValidationService,
                                   SessionTtlConfig sessionTtlConfig) {
        this.userStore = userStore;
        this.sessionStore = sessionStore;
        this.credentialHasher = credentialHasher;
        this.sessionIdGenerator = sessionIdGenerator;
        this.rolePermissionResolver = rolePermissionResolver;
        this.sessionValidationService = sessionValidationService;
        this.sessionTtlConfig = sessionTtlConfig;
    }

    /**
     * 用户登录。
     * <p>
     * 未知用户名和密码错误统一使用 AUTHENTICATION_FAILED，不泄漏用户名是否存在。
     */
    public UserSession login(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "username must not be blank");
        }
        if (password == null || password.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "password must not be empty");
        }

        UserAccount account = userStore.findByUsername(username)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.AUTHENTICATION_FAILED,
                        "Invalid username or password"
                ));

        if (!account.enabled()) {
            throw new AgentFrameworkException(AgentErrorCode.USER_DISABLED, "User account is disabled");
        }

        if (!credentialHasher.matches(password, account.credentialHash())) {
            throw new AgentFrameworkException(AgentErrorCode.AUTHENTICATION_FAILED, "Invalid username or password");
        }

        // 解析权限
        Set<String> permissions = rolePermissionResolver.resolvePermissions(account.roleNames());

        // 创建会话
        Instant now = Instant.now();
        Instant expiresAt = sessionTtlConfig.ttlSeconds() > 0
                ? now.plusSeconds(sessionTtlConfig.ttlSeconds())
                : null;

        UserSession session = new UserSession(
                sessionIdGenerator.generate(),
                account.userId(),
                account.username(),
                account.roleNames(),
                permissions,
                now,
                expiresAt
        );

        sessionStore.save(session);

        log.info("User logged in: userId={}, username={}", account.userId(), account.username());

        return session;
    }

    /**
     * 用户登出。
     * <p>
     * 先校验 Session，只删除当前 sessionId，不删除该用户全部 Session。操作幂等。
     */
    public void logout(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // 先校验 Session 存在且有效
        sessionValidationService.requireValidSession(sessionId);
        sessionStore.delete(sessionId);
        log.info("Session deleted on logout");
    }

    /**
     * 查询当前会话信息。
     * <p>
     * 通过 SessionValidationService 校验。
     */
    public UserSession getSession(String sessionId) {
        return sessionValidationService.requireValidSession(sessionId);
    }
}
