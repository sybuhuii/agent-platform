package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 创建用户请求 DTO。
 * <p>
 * 不允许提交 userId、credentialHash、permissions 或 sessionId。
 */
public record CreateUserRequest(
        String username,
        String password,
        Set<String> roleNames
) {
}
