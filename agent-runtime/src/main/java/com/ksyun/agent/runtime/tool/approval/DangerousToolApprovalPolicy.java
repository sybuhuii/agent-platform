package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.approval.InterruptReason;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.core.tool.ToolRiskLevel;

/**
 * 危险工具审批策略接口。
 * <p>
 * 纯 Java 接口，不依赖 Spring 或 ToolRegistry。
 * 策略只判断"是否需要审批"，不执行审批、不修改 RunContext。
 * 不得根据角色名称做特殊绕行。
 * <p>
 * 规则：
 * - riskLevel == HIGH 时需要审批
 * - riskLevel != HIGH 时不需要审批
 * - RunContext 为空时不默认放行
 * - 角色名称 ADMIN 本身不代表自动放行
 */
public interface DangerousToolApprovalPolicy {

    /**
     * 判断指定工具在当前运行上下文中是否需要人工审批。
     *
     * @param toolDefinition 工具定义
     * @param runContext      运行上下文
     * @return true 表示需要审批后才能执行
     */
    boolean requiresApproval(ToolDefinition toolDefinition, RunContext runContext);

    /**
     * 获取中断原因。
     *
     * @param toolDefinition 工具定义
     * @return 中断原因枚举
     */
    InterruptReason interruptReason(ToolDefinition toolDefinition);
}
