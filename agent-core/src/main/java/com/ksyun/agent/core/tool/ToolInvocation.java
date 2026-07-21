package com.ksyun.agent.core.tool;

import com.ksyun.agent.core.run.RunContext;

/**
 * 工具调用上下文，将 ToolCall 与运行上下文绑定。
 *
 * @param toolCall   工具调用请求
 * @param runContext 运行上下文
 */
public record ToolInvocation(
        ToolCall toolCall,
        RunContext runContext
) {
}
