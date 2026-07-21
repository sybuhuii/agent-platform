package com.ksyun.agent.core.tool;

/**
 * 工具定义。
 *
 * @param name              工具名称
 * @param description       工具描述
 * @param inputSchema       JSON Schema 字符串
 * @param requiredPermission 所需权限
 * @param riskLevel         风险等级
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        String requiredPermission,
        ToolRiskLevel riskLevel
) {
}
