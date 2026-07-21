package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具异常处理拦截器，作为最外层拦截器捕获整个工具执行链异常。
 * <p>
 * 捕获 AgentFrameworkException 时，将其错误码和安全错误信息转换为 ToolResult.failure。
 * 捕获其他异常时，记录完整堆栈，向调用方返回通用安全提示。
 * Error 等 JVM 严重错误不吞掉。
 */
public class ToolExceptionHandlingInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolExceptionHandlingInterceptor.class);

    @Override
    public int order() {
        return -1000;
    }

    @Override
    public ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain) {
        try {
            return chain.proceed(invocation);
        } catch (AgentFrameworkException e) {
            log.warn("Tool execution failed with framework error: toolName={}, toolCallId={}, runId={}, errorCode={}",
                    invocation.toolCall().name(),
                    invocation.toolCall().id(),
                    invocation.runContext().runId(),
                    e.getErrorCode(),
                    e);
            return ToolResult.failure(
                    e.getErrorCode().name(),
                    e.getMessage() != null ? e.getMessage() : "Tool execution failed"
            );
        } catch (Error e) {
            // JVM 严重错误不吞掉，向上抛出
            throw e;
        } catch (Exception e) {
            log.error("Tool execution failed with unexpected error: toolName={}, toolCallId={}, runId={}",
                    invocation.toolCall().name(),
                    invocation.toolCall().id(),
                    invocation.runContext().runId(),
                    e);
            return ToolResult.failure(
                    AgentErrorCode.TOOL_EXECUTION_FAILED.name(),
                    "Tool execution failed due to an internal error"
            );
        }
    }
}
