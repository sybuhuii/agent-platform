package com.ksyun.agent.core.tool;

/**
 * Agent 工具接口。
 * <p>
 * 不包含 Spring AI 注解。
 */
public interface AgentTool {

    /**
     * 返回工具定义。
     */
    ToolDefinition definition();

    /**
     * 执行工具调用。
     */
    ToolResult execute(ToolInvocation invocation);
}
