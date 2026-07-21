package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;

/**
 * 工具执行链，传递到下一个拦截器或最终执行。
 */
@FunctionalInterface
public interface ToolExecutionChain {

    ToolResult proceed(ToolInvocation invocation);
}
