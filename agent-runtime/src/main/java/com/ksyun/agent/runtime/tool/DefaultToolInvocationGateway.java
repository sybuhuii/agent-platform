package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 默认工具调用网关实现。
 * <p>
 * 接收 ToolInvocation，校验入参，创建新的执行链实例，调用拦截器链后进入终端执行器。
 * 每次调用都创建新的 DefaultToolExecutionChain，确保线程安全。
 * 不得直接包含具体工具业务逻辑，不得捕获异常后静默返回空值。
 */
public class DefaultToolInvocationGateway implements ToolInvocationGateway {

    private final List<ToolInterceptor> interceptors;
    private final ToolExecutionChain terminal;

    public DefaultToolInvocationGateway(List<ToolInterceptor> interceptors,
                                        ToolExecutionChain terminal) {
        List<ToolInterceptor> sorted = interceptors == null
                ? List.of()
                : interceptors.stream()
                .sorted(Comparator.comparingInt(ToolInterceptor::order))
                .toList();
        this.interceptors = Collections.unmodifiableList(sorted);
        this.terminal = terminal;
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        ToolResult validationError = validate(invocation);
        if (validationError != null) {
            return validationError;
        }

        DefaultToolExecutionChain chain = new DefaultToolExecutionChain(
                interceptors, terminal, 0
        );
        return chain.proceed(invocation);
    }

    /**
     * 校验入参，非法时返回结构化失败 ToolResult，合法时返回 null。
     */
    private ToolResult validate(ToolInvocation invocation) {
        if (invocation == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "ToolInvocation must not be null"
            );
        }
        if (invocation.toolCall() == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "ToolCall must not be null"
            );
        }
        if (invocation.toolCall().name() == null || invocation.toolCall().name().isBlank()) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Tool name must not be blank"
            );
        }
        if (invocation.runContext() == null) {
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "RunContext must not be null"
            );
        }
        return null;
    }
}
