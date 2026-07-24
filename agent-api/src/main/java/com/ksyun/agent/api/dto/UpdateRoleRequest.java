package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 更新角色权限请求 DTO。
 * <p>
 * 只包含可修改的字段：description 和 permissionCodes。
 * 不允许修改 roleName（路径参数已指定）。
 */
public record UpdateRoleRequest(
        String description,
        Set<String> permissionCodes
) {
}
