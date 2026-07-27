package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.tool.approval.AgentInterruptSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Set;

/**
 * 工具审计拦截器，记录结构化日志。
 * <p>
 * 捕获 AgentInterruptSignal 时记录安全的 APPROVAL_REQUIRED/SUSPENDED，
 * 原样抛出，不记录为成功。
 * 不记录完整参数、Session、权限集合或 stateData。
 * 不把正常中断记录为系统 ERROR。
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
        Set<String> argumentKeys = invocation.toolCall().arguments().keySet();

        Instant startTime = Instant.now();
        ToolResult result = null;
        Throwable caught = null;

        try {
            result = chain.proceed(invocation);
        } catch (AgentInterruptSignal e) {
            // 审批中断：记录安全状态，原样抛出
            Instant endTime = Instant.now();
            long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
            auditLog.info(
                    "toolName={} toolCallId={} runId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false errorCode=APPROVAL_REQUIRED status=SUSPENDED",
                    toolName, toolCallId, runId, userId,
                    argumentKeys, startTime, endTime, durationMs
            );
            throw e;
        } catch (Throwable t) {
            caught = t;
        }

        Instant endTime = Instant.now();
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

        if (caught != null) {
            auditLog.info(
                    "toolName={} toolCallId={} runId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false errorCode=UNCAUGHT_EXCEPTION",
                    toolName, toolCallId, runId, userId,
                    argumentKeys, startTime, endTime, durationMs
            );
            // 审计拦截器不修改结果，重新抛出异常让外层处理
            if (caught instanceof Error e) {
                throw e;
            }
            throw (RuntimeException) caught;
        }

        auditLog.info(
                "toolName={} toolCallId={} runId={} userId={} "
                        + "argumentKeys={} startTime={} endTime={} durationMs={} "
                        + "success={} errorCode={}",
                toolName, toolCallId, runId, userId,
                argumentKeys, startTime, endTime, durationMs,
                result.success(),
                result.errorCode()
        );

        return result;
    }
}
