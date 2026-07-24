package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.CredentialHasher;
import com.ksyun.agent.core.security.SecurityPermissionCodes;
import com.ksyun.agent.core.security.UserAccount;
import com.ksyun.agent.core.security.UserIdGenerator;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.RoleStore;
import com.ksyun.agent.core.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理应用服务。
 * <p>
 * 提供用户列表、创建、更新和密码重置功能。
 * 所有操作需通过权限校验，权限不足抛 PERMISSION_DENIED。
 * <p>
 * 自我锁定保护：管理员不得修改自己的角色、禁用自己或重置自己的密码。
 * <p>
 * 纯 Java 实现，不依赖 Spring、HttpServletRequest 或 ThreadLocal。
 * 保持无状态和线程安全。
 */
public class UserManagementApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementApplicationService.class);

    private final UserStore userStore;
    private final RoleStore roleStore;
    private final CredentialHasher credentialHasher;
    private final PermissionAuthorizationService permissionAuthorizationService;
    private final UserSessionRevocationService sessionRevocationService;
    private final UserIdGenerator userIdGenerator;

    public UserManagementApplicationService(UserStore userStore,
                                             RoleStore roleStore,
                                             CredentialHasher credentialHasher,
                                             PermissionAuthorizationService permissionAuthorizationService,
                                             UserSessionRevocationService sessionRevocationService,
                                             UserIdGenerator userIdGenerator) {
        this.userStore = userStore;
        this.roleStore = roleStore;
        this.credentialHasher = credentialHasher;
        this.permissionAuthorizationService = permissionAuthorizationService;
        this.sessionRevocationService = sessionRevocationService;
        this.userIdGenerator = userIdGenerator;
    }

    /**
     * 查询所有用户。
     * 需要 security:user:read 权限。
     */
    public Collection<UserSummary> listUsers(UserSession operator) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.USER_READ);
        return userStore.list().stream()
                .map(UserSummary::from)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 创建新用户。
     * 需要 security:user:write 权限。
     */
    public UserSummary createUser(UserSession operator, String username, CharSequence password, Set<String> roleNames) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.USER_WRITE);

        // 校验 username
        if (username == null || username.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "username must not be blank");
        }

        // 校验 password
        if (password == null || password.length() == 0) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "password must not be empty");
        }

        // 校验角色名称非空
        if (roleNames == null || roleNames.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "roleNames must not be empty");
        }

        // 用户名不可重复
        if (userStore.existsByUsername(username)) {
            throw new AgentFrameworkException(AgentErrorCode.USER_ALREADY_EXISTS,
                    "Username already exists: " + username);
        }

        // 校验全部角色存在
        validateRolesExist(roleNames);

        String userId = userIdGenerator.nextUserId();
        String credentialHash = credentialHasher.hash(password);

        UserAccount account = new UserAccount(
                userId,
                username,
                credentialHash,
                Collections.unmodifiableSet(new HashSet<>(roleNames)),
                true
        );

        userStore.save(account);

        log.info("User created: userId={}, username={}, operatorUserId={}", userId, username, operator.userId());
        // 创建用户不创建 Session，不自动登录

        return UserSummary.from(account);
    }

    /**
     * 更新用户角色和启用状态。
     * 需要 security:user:write 权限。
     * 自我锁定保护：不得修改自己的角色或禁用自己。
     */
    public UserSummary updateUser(UserSession operator, String userId, Set<String> roleNames, boolean enabled) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.USER_WRITE);

        // 校验 userId 非空
        if (userId == null || userId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "userId must not be blank");
        }

        // 校验角色名称非空
        if (roleNames == null || roleNames.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "roleNames must not be empty");
        }

        // 自我锁定保护：不得修改自己的角色或禁用自己
        if (operator.userId().equals(userId)) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Cannot modify your own roles or enabled status");
        }

        // 查找现有用户
        UserAccount existing = userStore.findById(userId)
                .orElseThrow(() -> new AgentFrameworkException(AgentErrorCode.USER_NOT_FOUND,
                        "User not found: " + userId));

        // 校验全部角色存在
        validateRolesExist(roleNames);

        UserAccount updated = new UserAccount(
                existing.userId(),
                existing.username(),
                existing.credentialHash(),
                Collections.unmodifiableSet(new HashSet<>(roleNames)),
                enabled
        );

        userStore.update(updated);

        // 角色或状态变化后撤销该用户全部旧 Session
        sessionRevocationService.revokeAllForUser(userId);

        log.info("User updated: userId={}, enabled={}, operatorUserId={}", userId, enabled, operator.userId());
        return UserSummary.from(updated);
    }

    /**
     * 重置用户密码。
     * 需要 security:user:write 权限。
     * 自我锁定保护：管理员不得重置自己的密码。
     */
    public void resetPassword(UserSession operator, String userId, CharSequence newPassword) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.USER_WRITE);

        // 校验 userId 非空
        if (userId == null || userId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "userId must not be blank");
        }

        // 校验新密码非空
        if (newPassword == null || newPassword.length() == 0) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "newPassword must not be empty");
        }

        // 自我锁定保护：管理员不得重置自己的密码
        if (operator.userId().equals(userId)) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Cannot reset your own password through admin interface");
        }

        // 查找现有用户
        UserAccount existing = userStore.findById(userId)
                .orElseThrow(() -> new AgentFrameworkException(AgentErrorCode.USER_NOT_FOUND,
                        "User not found: " + userId));

        String newCredentialHash = credentialHasher.hash(newPassword);

        UserAccount updated = new UserAccount(
                existing.userId(),
                existing.username(),
                newCredentialHash,
                existing.roleNames(),
                existing.enabled()
        );

        userStore.update(updated);

        // 密码变化后撤销该用户全部旧 Session
        sessionRevocationService.revokeAllForUser(userId);

        log.info("User password reset: userId={}, operatorUserId={}", userId, operator.userId());
        // 日志不包含密码
    }

    private void validateRolesExist(Set<String> roleNames) {
        for (String roleName : roleNames) {
            if (roleName == null || roleName.isBlank()) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "roleName must not be blank");
            }
            roleStore.find(roleName)
                    .orElseThrow(() -> new AgentFrameworkException(AgentErrorCode.ROLE_NOT_FOUND,
                            "Role not found: " + roleName));
        }
    }
}
