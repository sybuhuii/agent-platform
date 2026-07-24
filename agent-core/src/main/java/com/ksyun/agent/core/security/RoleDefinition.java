package com.ksyun.agent.core.security;

import java.io.Serializable;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * 角色定义，不可变。
 * <p>
 * 角色定义只表达权限，不执行权限判断。
 * 角色名称采用稳定格式，例如 ADMIN、VISITOR。
 * 不得直接保存 UserAccount 列表。
 * 不得依赖 ToolRegistry。
 * 不得保存 AgentTool 实例。
 * 不得硬编码只有 admin 和 visitor，框架应允许未来增加角色。
 *
 * @param roleName         角色名称，不能为空
 * @param description      角色描述
 * @param permissionCodes  权限编码集合，不可变，不得包含空字符串
 */
public record RoleDefinition(
        String roleName,
        String description,
        Set<String> permissionCodes
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public RoleDefinition {
        Objects.requireNonNull(roleName, "roleName must not be null");
        if (roleName.isBlank()) {
            throw new IllegalArgumentException("roleName must not be blank");
        }
        if (permissionCodes == null) {
            throw new IllegalArgumentException("permissionCodes must not be null");
        }
        for (String code : permissionCodes) {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("permissionCodes must not contain blank strings");
            }
        }
        roleName = roleName.trim();
        permissionCodes = Collections.unmodifiableSet(permissionCodes);
    }
}
