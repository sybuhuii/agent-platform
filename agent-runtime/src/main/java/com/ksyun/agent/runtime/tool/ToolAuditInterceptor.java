package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;

/**
 * 工具审计拦截器，记录结构化日志。
 * <p>
 * 不记录完整原始参数和返回内容，避免敏感数据泄露。
 * 审计拦截器不能修改业务执行结果。
 */
public class ToolAuditInterceptor implements ToolInterceptor {

    private static final Logger auditLog = LoggerFactory.getLogger("TOOL_AUDIT");

    @Override
    public int order() {
        return -500;
    }

    @Override
    public ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain) {
        String toolName = invocation.toolCall().name();
        String toolCallId = invocation.toolCall().id();
        String runId = invocation.runContext().runId();
        String userId = invocation.runContext().userId();
        long threadId = Thread.currentThread().getId();
        Set<String> argumentKeys = invocation.toolCall().arguments().keySet();

        Instant startTime = Instant.now();
        ToolResult result = null;
        Throwable caught = null;

        try {
            result = chain.proceed(invocation);
        } catch (Throwable t) {
            caught = t;
        }

        Instant endTime = Instant.now();
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

        if (caught != null) {
            auditLog.info(
                    "toolName={} toolCallId={} runId={} threadId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false errorCode=UNCAUGHT_EXCEPTION",
                    toolName, toolCallId, runId, threadId, userId,
                    argumentKeys, startTime, endTime, durationMs
            );
            // 审计拦截器不修改结果，重新抛出异常让外层处理
            if (caught instanceof Error e) {
                throw e;
            }
            throw (RuntimeException) caught;
        }

        auditLog.info(
                "toolName={} toolCallId={} runId={} threadId={} userId={} "
                        + "argumentKeys={} startTime={} endTime={} durationMs={} "
                        + "success={} errorCode={}",
                toolName, toolCallId, runId, threadId, userId,
                argumentKeys, startTime, endTime, durationMs,
                result.success(),
                result.errorCode()
        );

        return result;
    }
}
