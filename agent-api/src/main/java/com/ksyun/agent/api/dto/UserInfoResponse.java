package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 用户身份信息响应 DTO，用于 GET /api/auth/me。
 * <p>
 * 不返回 sessionId。
 * 不返回 credentialHash。
 */
public record UserInfoResponse(
        String userId,
        String username,
        Set<String> roles,
        Set<String> permissions
) {
}
