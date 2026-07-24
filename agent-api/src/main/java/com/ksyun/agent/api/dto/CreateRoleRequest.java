package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 创建角色请求 DTO。
 * <p>
 * 不允许修改 roleName（创建时确定）。
 */
public record CreateRoleRequest(
        String roleName,
        String description,
        Set<String> permissionCodes
) {
}
