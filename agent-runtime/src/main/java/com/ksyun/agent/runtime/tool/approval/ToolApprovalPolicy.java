package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolDefinition;

/**
 * 统一工具审批策略接口。
 * <p>
 * 纯 Java 接口，不依赖 Spring 或 ToolRegistry。
 * 策略只判断"是否需要审批"，不执行审批、不修改 RunContext。
 * <p>
 * 规则：
 * - LOW、MEDIUM 不审批
 * - HIGH 必须审批
 * - ADMIN 不绕过
 * - tool:*:invoke 不绕过
 * - RunContext 缺失时失败关闭
 * - ToolDefinition 缺失沿用 TOOL_NOT_FOUND
 * - displayReason 不包含参数、Session、权限集合和思维链
 * - runtime 类型保持纯 Java，不添加 Spring 注解
 * <p>
 * 禁止同时保留两套语义重复的审批策略。
 */
public interface ToolApprovalPolicy {

    /**
     * 评估工具调用是否需要人工审批。
     *
     * @param definition 工具定义
     * @param call       工具调用
     * @param context    运行上下文
     * @return 审批需求评估结果
     */
    ToolApprovalRequirement evaluate(ToolDefinition definition, ToolCall call, RunContext context);
}
