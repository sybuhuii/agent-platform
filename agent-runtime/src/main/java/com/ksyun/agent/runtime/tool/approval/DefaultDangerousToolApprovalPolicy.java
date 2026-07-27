package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.approval.InterruptReason;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolRiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认危险工具审批策略。
 * <p>
 * 规则：
 * - riskLevel == HIGH 时需要人工审批
 * - riskLevel != HIGH 时不需要审批
 * - RunContext 为空时不默认放行（但也不会拒绝——需要审批由 riskLevel 决定）
 * - 不得根据角色名称做特殊绕行，ADMIN 角色也需要审批 HIGH 风险工具
 * <p>
 * 纯 Java 实现，不依赖 Spring 或 ToolRegistry。
 * 保持无状态和线程安全。
 */
public class DefaultDangerousToolApprovalPolicy implements DangerousToolApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultDangerousToolApprovalPolicy.class);

    @Override
    public boolean requiresApproval(ToolDefinition toolDefinition, RunContext runContext) {
        if (toolDefinition == null) {
            return false;
        }

        boolean highRisk = toolDefinition.riskLevel() == ToolRiskLevel.HIGH;

        if (highRisk) {
            log.debug("Tool requires approval: toolName={}, riskLevel=HIGH, userId={}",
                    toolDefinition.name(),
                    runContext != null ? runContext.userId() : "unknown");
        }

        return highRisk;
    }

    @Override
    public InterruptReason interruptReason(ToolDefinition toolDefinition) {
        if (toolDefinition != null && toolDefinition.riskLevel() == ToolRiskLevel.HIGH) {
            return InterruptReason.TOOL_RISK_HIGH;
        }
        return InterruptReason.CUSTOM;
    }
}
