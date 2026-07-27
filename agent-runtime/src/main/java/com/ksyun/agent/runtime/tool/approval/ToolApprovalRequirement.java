package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.tool.ToolRiskLevel;

import java.util.Objects;

/**
 * 工具审批需求评估结果，不可变。
 *
 * @param required      是否需要审批
 * @param riskLevel     风险等级
 * @param reasonCode    原因码
 * @param displayReason 安全展示原因，不包含参数、Session、权限集合和思维链
 */
public record ToolApprovalRequirement(
        boolean required,
        ToolRiskLevel riskLevel,
        String reasonCode,
        String displayReason
) {

    public ToolApprovalRequirement {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        Objects.requireNonNull(displayReason, "displayReason must not be null");
        if (displayReason.isBlank()) {
            throw new IllegalArgumentException("displayReason must not be blank");
        }
    }

    /**
     * 不需要审批。
     */
    public static ToolApprovalRequirement notRequired(ToolRiskLevel riskLevel, String reasonCode, String displayReason) {
        return new ToolApprovalRequirement(false, riskLevel, reasonCode, displayReason);
    }

    /**
     * 需要审批。
     */
    public static ToolApprovalRequirement required(ToolRiskLevel riskLevel, String reasonCode, String displayReason) {
        return new ToolApprovalRequirement(true, riskLevel, reasonCode, displayReason);
    }
}
