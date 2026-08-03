package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.CredentialHasher;
import com.ksyun.agent.core.security.RolePermissionResolver;
import com.ksyun.agent.core.security.SessionIdGenerator;
import com.ksyun.agent.core.security.UserAccount;
import com.ksyun.agent.core.security.UserIdGenerator;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.RoleStore;
import com.ksyun.agent.core.store.SessionStore;
import com.ksyun.agent.core.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_.-]{3,32}");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserStore userStore;
    private final SessionStore sessionStore;
    private final CredentialHasher credentialHasher;
    private final SessionIdGenerator sessionIdGenerator;
    private final RolePermissionResolver rolePermissionResolver;
    private final SessionValidationService sessionValidationService;
    private final SessionTtlConfig sessionTtlConfig;
    private final UserIdGenerator userIdGenerator;
    private final RoleStore roleStore;
    private final String registrationRoleName;

    public AuthApplicationService(UserStore userStore,
                                   SessionStore sessionStore,
                                   CredentialHasher credentialHasher,
                                   SessionIdGenerator sessionIdGenerator,
                                   RolePermissionResolver rolePermissionResolver,
                                   SessionValidationService sessionValidationService,
                                   SessionTtlConfig sessionTtlConfig,
                                   UserIdGenerator userIdGenerator,
                                   RoleStore roleStore,
                                   String registrationRoleName) {
        this.userStore = userStore;
        this.sessionStore = sessionStore;
        this.credentialHasher = credentialHasher;
        this.sessionIdGenerator = sessionIdGenerator;
        this.rolePermissionResolver = rolePermissionResolver;
        this.sessionValidationService = sessionValidationService;
        this.sessionTtlConfig = sessionTtlConfig;
        this.userIdGenerator = userIdGenerator;
        this.roleStore = roleStore;
        this.registrationRoleName = requireRegistrationRoleName(registrationRoleName);
    }

    /**
     * 公开注册。角色由服务端配置决定，调用方不能提交角色或权限。
     * 注册成功后创建本次独立 Session，避免 Controller 编排“注册后再登录”。
     */
    public UserSession register(String username, String password, String confirmPassword) {
        String normalizedUsername = validateRegistrationInput(username, password, confirmPassword);

        if (userStore.existsByUsername(normalizedUsername)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.USER_ALREADY_EXISTS,
                    "Username is already in use"
            );
        }

        roleStore.find(registrationRoleName)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.ROLE_NOT_FOUND,
                        "Registration role is not available"
                ));

        UserAccount account = new UserAccount(
                userIdGenerator.nextUserId(),
                normalizedUsername,
                credentialHasher.hash(password),
                Collections.singleton(registrationRoleName),
                true
        );
        userStore.save(account);

        UserSession session = createSession(account);
        log.info("User registered: userId={}, username={}", account.userId(), account.username());
        return session;
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

        UserSession session = createSession(account);

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

    private UserSession createSession(UserAccount account) {
        Set<String> permissions = rolePermissionResolver.resolvePermissions(account.roleNames());
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
        return session;
    }

    private String validateRegistrationInput(String username, String password, String confirmPassword) {
        if (username == null || !USERNAME_PATTERN.matcher(UserAccount.normalizeUsername(username)).matches()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Username must be 3-32 characters and contain only letters, numbers, dots, hyphens or underscores"
            );
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Password must contain at least 8 characters"
            );
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Password is too long"
            );
        }
        if (!password.equals(confirmPassword)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Passwords do not match"
            );
        }
        return UserAccount.normalizeUsername(username);
    }

    private String requireRegistrationRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("registrationRoleName must not be blank");
        }
        return roleName.trim();
    }
}
