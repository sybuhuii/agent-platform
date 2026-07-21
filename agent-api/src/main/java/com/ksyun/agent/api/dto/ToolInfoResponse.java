package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.tool.ToolRiskLevel;

/**
 * Tool 元信息响应 DTO。
 * <p>
 * 不暴露权限敏感数据和内部实现类名。
 */
public record ToolInfoResponse(
        String name,
        String description,
        ToolRiskLevel riskLevel
) {
}
