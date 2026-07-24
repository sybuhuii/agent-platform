package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 会话信息响应 DTO。
 * <p>
 * 不返回 credentialHash、userId。
 * 不返回完整权限列表。
 */
public record SessionInfoResponse(
        String sessionId,
        String username,
        Set<String> roles,
        long createdAtEpochMillis,
        long expiresAtEpochMillis
) {
}
