package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;

import java.util.List;

/**
 * 线程安全的拦截器链实现。
 * <p>
 * 持有不可变拦截器列表、终端执行器和当前索引。
 * 每次调用 proceed 时，若有拦截器则调用当前拦截器并传入索引+1的新链对象；
 * 若无拦截器则调用终端执行器。
 * <p>
 * 禁止把 currentIndex 设计成会被多线程共享修改的成员变量。
 * 同一个 Gateway 可以并发执行多个 ToolInvocation。
 */
public class DefaultToolExecutionChain implements ToolExecutionChain {

    private final List<ToolInterceptor> interceptors;
    private final ToolExecutionChain terminal;
    private final int currentIndex;

    /**
     * @param interceptors 不可变拦截器列表
     * @param terminal     终端执行器
     * @param currentIndex 当前拦截器索引
     */
    public DefaultToolExecutionChain(List<ToolInterceptor> interceptors,
                                     ToolExecutionChain terminal,
                                     int currentIndex) {
        this.interceptors = interceptors;
        this.terminal = terminal;
        this.currentIndex = currentIndex;
    }

    @Override
    public ToolResult proceed(ToolInvocation invocation) {
        if (currentIndex < interceptors.size()) {
            ToolInterceptor interceptor = interceptors.get(currentIndex);
            ToolExecutionChain next = new DefaultToolExecutionChain(
                    interceptors, terminal, currentIndex + 1
            );
            return interceptor.intercept(invocation, next);
        }
        return terminal.proceed(invocation);
    }
}
