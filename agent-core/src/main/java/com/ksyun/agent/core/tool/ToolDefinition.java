package com.ksyun.agent.core.tool;

import java.util.Objects;

/**
 * 工具定义。
 *
 * @param name              工具名称
 * @param description       工具描述
 * @param inputSchema       JSON Schema 字符串
 * @param requiredPermission 所需权限
 * @param riskLevel         风险等级，不得为 null，普通工具默认 LOW
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        String requiredPermission,
        ToolRiskLevel riskLevel
) {

    public ToolDefinition {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }

    /**
     * 兼容构造器：不指定风险等级时默认 LOW。
     */
    public ToolDefinition(String name, String description, String inputSchema, String requiredPermission) {
        this(name, description, inputSchema, requiredPermission, ToolRiskLevel.LOW);
    }
}
