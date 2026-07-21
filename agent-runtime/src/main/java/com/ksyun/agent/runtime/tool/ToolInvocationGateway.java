package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;

/**
 * 工具调用网关，统一入口。
 */
public interface ToolInvocationGateway {

    /**
     * 通过网关调用工具。
     */
    ToolResult invoke(ToolInvocation invocation);
}
