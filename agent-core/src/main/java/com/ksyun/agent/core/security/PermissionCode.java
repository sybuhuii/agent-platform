package com.ksyun.agent.core.security;

import java.util.Objects;

/**
 * 统一权限编码值对象。
 * <p>
 * 权限编码集中由 {@link ToolPermissionCodes} 等工具类生成，
 * 业务代码不得散落字符串拼接。
 * value 不能为空、不能包含空格或不可见字符。
 */
public record PermissionCode(String value) {

    public PermissionCode {
        Objects.requireNonNull(value, "PermissionCode value must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("PermissionCode value must not be blank");
        }
        if (trimmed.chars().anyMatch(c -> c <= ' ' || c == '')) {
            throw new IllegalArgumentException(
                    "PermissionCode value must not contain whitespace or invisible characters: " + trimmed);
        }
        value = trimmed;
    }
}
