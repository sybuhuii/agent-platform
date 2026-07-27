package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolRiskLevel;

/**
 * 默认工具审批策略。
 * <p>
 * 规则：
 * - LOW、MEDIUM 不审批
 * - HIGH 必须审批
 * - ADMIN 不绕过
 * - tool:*:invoke 不绕过（ACL 授权和人工审批是两层独立约束）
 * - RunContext 缺失时失败关闭（需要审批）
 * - ToolDefinition 缺失沿用 TOOL_NOT_FOUND
 * <p>
 * 纯 Java 实现，不依赖 Spring。保持无状态和线程安全。
 */
public class DefaultToolApprovalPolicy implements ToolApprovalPolicy {

    private static final String REASON_APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    private static final String REASON_LOW_RISK = "LOW_RISK";
    private static final String REASON_MEDIUM_RISK = "MEDIUM_RISK";
    private static final String REASON_TOOL_NOT_FOUND = "TOOL_NOT_FOUND";
    private static final String REASON_MISSING_CONTEXT = "MISSING_CONTEXT";

    @Override
    public ToolApprovalRequirement evaluate(ToolDefinition definition, ToolCall call, RunContext context) {
        // RunContext 缺失时失败关闭
        if (context == null) {
            return ToolApprovalRequirement.required(
                    ToolRiskLevel.HIGH,
                    REASON_MISSING_CONTEXT,
                    "运行上下文缺失，默认需要审批"
            );
        }

        // ToolDefinition 缺失沿用 TOOL_NOT_FOUND
        if (definition == null) {
            return ToolApprovalRequirement.required(
                    ToolRiskLevel.HIGH,
                    REASON_TOOL_NOT_FOUND,
                    "工具定义不存在"
            );
        }

        ToolRiskLevel riskLevel = definition.riskLevel();

        // HIGH 必须审批，ADMIN 不绕过，tool:*:invoke 不绕过
        if (riskLevel == ToolRiskLevel.HIGH) {
            return ToolApprovalRequirement.required(
                    riskLevel,
                    REASON_APPROVAL_REQUIRED,
                    "高风险工具需要人工审批"
            );
        }

        // MEDIUM 本批不审批
        if (riskLevel == ToolRiskLevel.MEDIUM) {
            return ToolApprovalRequirement.notRequired(
                    riskLevel,
                    REASON_MEDIUM_RISK,
                    "中等风险工具无需审批"
            );
        }

        // LOW 不审批
        return ToolApprovalRequirement.notRequired(
                riskLevel,
                REASON_LOW_RISK,
                "低风险工具无需审批"
        );
    }
}
