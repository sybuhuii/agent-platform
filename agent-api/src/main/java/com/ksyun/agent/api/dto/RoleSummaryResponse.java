package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * 角色摘要响应 DTO。
 * <p>
 * 不暴露内部 Store 实现信息。
 */
public record RoleSummaryResponse(
        String roleName,
        String description,
        Set<String> permissionCodes
) {
}
