package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 用户摘要响应 DTO。
 * <p>
 * 不包含 credentialHash。
 */
public record UserSummaryResponse(
        String userId,
        String username,
        Set<String> roleNames,
        boolean enabled
) {
}
