package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.security.RoleDefinition;

import java.util.Set;

/**
 * 角色摘要信息，用于管理 API 响应。
 * <p>
 * 不暴露内部 Store 实现信息。
 * 使用不可变 record 类型。
 *
 * @param roleName        角色名称
 * @param description     角色描述
 * @param permissionCodes 权限编码集合
 */
public record RoleSummary(
        String roleName,
        String description,
        Set<String> permissionCodes
) {
    /**
     * 从 RoleDefinition 创建摘要。
     */
    public static RoleSummary from(RoleDefinition definition) {
        return new RoleSummary(
                definition.roleName(),
                definition.description(),
                definition.permissionCodes()
        );
    }
}
