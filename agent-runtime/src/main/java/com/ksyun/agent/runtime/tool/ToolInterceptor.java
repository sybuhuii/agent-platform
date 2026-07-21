package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;

/**
 * 工具拦截器，可在工具执行前后插入逻辑。
 */
public interface ToolInterceptor {

    /**
     * 拦截器排序值，越小越先进入调用链（处于链外层）。
     * <p>
     * 不依赖 Spring Ordered 或 @Order，保持框架无关。
     */
    default int order() {
        return 0;
    }

    /**
     * 拦截工具调用。
     *
     * @param invocation 工具调用上下文
     * @param chain      执行链，调用 chain.proceed() 继续传递
     * @return 工具执行结果
     */
    ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain);
}
