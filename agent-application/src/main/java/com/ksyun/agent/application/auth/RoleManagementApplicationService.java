package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.PermissionCode;
import com.ksyun.agent.core.security.RoleDefinition;
import com.ksyun.agent.core.security.SecurityPermissionCodes;
import com.ksyun.agent.core.security.UserAccount;
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
 * 角色管理应用服务。
 * <p>
 * 提供角色列表、创建和权限更新功能。
 * 所有操作需通过权限校验，权限不足抛 PERMISSION_DENIED。
 * <p>
 * 修改角色权限后撤销所有受影响用户的旧 Session。
 * <p>
 * 纯 Java 实现，不依赖 Spring、HttpServletRequest 或 ThreadLocal。
 * 保持无状态和线程安全。
 */
public class RoleManagementApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RoleManagementApplicationService.class);

    private final RoleStore roleStore;
    private final UserStore userStore;
    private final PermissionAuthorizationService permissionAuthorizationService;
    private final UserSessionRevocationService sessionRevocationService;

    public RoleManagementApplicationService(RoleStore roleStore,
                                             UserStore userStore,
                                             PermissionAuthorizationService permissionAuthorizationService,
                                             UserSessionRevocationService sessionRevocationService) {
        this.roleStore = roleStore;
        this.userStore = userStore;
        this.permissionAuthorizationService = permissionAuthorizationService;
        this.sessionRevocationService = sessionRevocationService;
    }

    /**
     * 查询所有角色。
     * 需要 security:role:read 权限。
     */
    public Collection<RoleSummary> listRoles(UserSession operator) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.ROLE_READ);
        return roleStore.list().stream()
                .map(RoleSummary::from)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 创建新角色。
     * 需要 security:role:write 权限。
     */
    public RoleSummary createRole(UserSession operator, String roleName, String description, Set<String> permissionCodes) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.ROLE_WRITE);

        // 角色名称不能为空
        if (roleName == null || roleName.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "roleName must not be blank");
        }

        // 校验权限编码
        Set<String> validatedPermissions = validateAndNormalizePermissions(permissionCodes);

        // 角色名称 trim 和规范化（与 RoleDefinition 一致）
        String normalizedRoleName = roleName.trim();

        // 创建时角色不得已存在
        if (roleStore.find(normalizedRoleName).isPresent()) {
            throw new AgentFrameworkException(AgentErrorCode.ROLE_ALREADY_EXISTS,
                    "Role already exists: " + normalizedRoleName);
        }

        RoleDefinition definition = new RoleDefinition(
                normalizedRoleName,
                description,
                validatedPermissions
        );

        roleStore.save(definition);

        log.info("Role created: roleName={}, operatorUserId={}", normalizedRoleName, operator.userId());
        // 不自动创建用户

        return RoleSummary.from(definition);
    }

    /**
     * 更新角色权限和描述。
     * 需要 security:role:write 权限。
     * 修改角色权限后撤销所有受影响用户的旧 Session。
     */
    public RoleSummary updateRolePermissions(UserSession operator, String roleName, String description, Set<String> permissionCodes) {
        permissionAuthorizationService.requirePermission(operator, SecurityPermissionCodes.ROLE_WRITE);

        // 角色名称不能为空
        if (roleName == null || roleName.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "roleName must not be blank");
        }

        // 校验权限编码
        Set<String> validatedPermissions = validateAndNormalizePermissions(permissionCodes);

        // 更新时角色必须存在
        RoleDefinition existing = roleStore.find(roleName.trim())
                .orElseThrow(() -> new AgentFrameworkException(AgentErrorCode.ROLE_NOT_FOUND,
                        "Role not found: " + roleName.trim()));

        RoleDefinition updated = new RoleDefinition(
                existing.roleName(),
                description,
                validatedPermissions
        );

        roleStore.update(updated);

        // 查找所有包含该角色的用户
        Set<String> affectedUserIds = findUsersByRole(existing.roleName());

        // 撤销这些用户全部旧 Session
        int revokedCount = sessionRevocationService.revokeAllForUsers(affectedUserIds);

        log.info("Role permissions updated: roleName={}, affectedUsers={}, revokedSessions={}, operatorUserId={}",
                existing.roleName(), affectedUserIds.size(), revokedCount, operator.userId());

        return RoleSummary.from(updated);
    }

    /**
     * 逐项通过 PermissionCode 校验权限编码，返回规范化后的不可变集合。
     */
    private Set<String> validateAndNormalizePermissions(Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return Set.of();
        }

        Set<String> validated = new HashSet<>();
        for (String code : permissionCodes) {
            // 通过 PermissionCode 校验格式
            PermissionCode permissionCode = new PermissionCode(code);
            validated.add(permissionCode.value());
        }
        return Collections.unmodifiableSet(validated);
    }

    /**
     * 查找所有拥有指定角色的用户 ID。
     */
    private Set<String> findUsersByRole(String roleName) {
        return userStore.list().stream()
                .filter(user -> user.roleNames().contains(roleName))
                .map(UserAccount::userId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
