package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.registry.ToolRegistry;

/**
 * 终端工具执行器。
 * <p>
 * 从 ToolRegistry 查找 AgentTool 并执行。
 * 不实现参数校验、权限、审批或审计。
 */
public class TerminalToolExecutor implements ToolExecutionChain {

    private final ToolRegistry toolRegistry;

    public TerminalToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolResult proceed(ToolInvocation invocation) {
        String toolName = invocation.toolCall().name();
        AgentTool tool = toolRegistry.find(toolName).orElse(null);
        if (tool == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.TOOL_NOT_FOUND,
                    "Tool not found: " + toolName
            );
        }
        ToolResult result = tool.execute(invocation);
        if (result == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.TOOL_EXECUTION_FAILED,
                    "Tool '" + toolName + "' returned null result"
            );
        }
        return result;
    }
}
