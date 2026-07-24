package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 登录响应 DTO。
 * <p>
 * 不返回 credentialHash、userId（避免内部 ID 泄露）。
 * 不返回完整权限列表。
 */
public record LoginResponse(
        String sessionId,
        String username,
        Set<String> roles,
        long expiresAtEpochMillis
) {
}
