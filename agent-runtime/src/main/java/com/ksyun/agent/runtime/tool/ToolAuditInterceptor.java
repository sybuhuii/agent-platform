package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.tool.approval.AgentInterruptSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;

import java.time.Instant;
import java.util.Set;

/**
 * 工具审计拦截器，记录结构化日志。
 *
 * 不记录完整参数、Session、权限集合、工具完整结果或 stateData。
 */
public class ToolAuditInterceptor implements ToolInterceptor {

    private static final Logger auditLog =
            LoggerFactory.getLogger("TOOL_AUDIT");

    @Override
    public int order() {
        return -500;
    }

    @Override
    public ToolResult intercept(
            ToolInvocation invocation,
            ToolExecutionChain chain
    ) {
        String toolName = invocation.toolCall().name();
        String toolCallId = invocation.toolCall().id();
        String runId = invocation.runContext().runId();
        String threadId = invocation.runContext().threadId();
        String userId = invocation.runContext().userId();
        Set<String> argumentKeys =
                Set.copyOf(invocation.toolCall().arguments().keySet());

        Instant startTime = Instant.now();
        ToolResult result = null;
        Throwable caught = null;

        try {
            result = chain.proceed(invocation);
        } catch (AgentInterruptSignal e) {
            Instant endTime = Instant.now();
            long durationMs =
                    endTime.toEpochMilli() - startTime.toEpochMilli();

            auditLog.info(
                    "toolName={} toolCallId={} runId={} threadId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false errorCode=APPROVAL_REQUIRED status=SUSPENDED",
                    toolName,
                    toolCallId,
                    runId,
                    threadId,
                    userId,
                    argumentKeys,
                    startTime,
                    endTime,
                    durationMs
            );
            throw e;
        } catch (Throwable t) {
            caught = t;
        }

        Instant endTime = Instant.now();
        long durationMs =
                endTime.toEpochMilli() - startTime.toEpochMilli();

        if (caught != null) {
            auditLog.info(
                    "toolName={} toolCallId={} runId={} threadId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false errorCode=UNCAUGHT_EXCEPTION",
                    toolName,
                    toolCallId,
                    runId,
                    threadId,
                    userId,
                    argumentKeys,
                    startTime,
                    endTime,
                    durationMs
            );

            if (caught instanceof Error error) {
                throw error;
            }
            if (caught instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new AgentFrameworkException(
                    AgentErrorCode.TOOL_EXECUTION_FAILED,
                    "Unexpected failure in tool execution chain",
                    caught
            );
        }

        auditLog.info(
                "toolName={} toolCallId={} runId={} threadId={} userId={} "
                        + "argumentKeys={} startTime={} endTime={} durationMs={} "
                        + "success={} errorCode={}",
                toolName,
                toolCallId,
                runId,
                threadId,
                userId,
                argumentKeys,
                startTime,
                endTime,
                durationMs,
                result.success(),
                result.errorCode()
        );

        return result;
    }
}