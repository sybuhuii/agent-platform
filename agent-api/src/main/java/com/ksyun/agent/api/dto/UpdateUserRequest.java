package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 更新用户请求 DTO。
 * <p>
 * 只包含可修改的字段：roleNames 和 enabled。
 * 不允许修改 userId、username 或提交 credentialHash、permissions。
 */
public record UpdateUserRequest(
        Set<String> roleNames,
        boolean enabled
) {
}
