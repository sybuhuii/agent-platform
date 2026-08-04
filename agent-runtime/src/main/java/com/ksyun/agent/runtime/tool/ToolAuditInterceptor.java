package com.ksyun.agent.runtime.tool;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.sanitizer.SensitiveValueSanitizer;
import com.ksyun.agent.core.store.ToolAuditStore;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.core.tool.audit.ToolAuditIdGenerator;
import com.ksyun.agent.core.tool.audit.ToolAuditStatus;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditCompletion;
import com.ksyun.agent.core.tool.audit.ToolInvocationAuditStarted;
import com.ksyun.agent.runtime.tool.approval.AgentInterruptSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具审计拦截器，将结构化审计记录持久化到 {@link ToolAuditStore}。
 * <p>
 * 拦截器顺序 -500：在 ExceptionHandling(-1000) 之后，ACL(-200) 之前。
 * <p>
 * 审计语义：
 * <ul>
 *   <li>STARTED 记录在 chain.proceed() 之前写入，确保"无审计不执行"。</li>
 *   <li>终态记录（SUCCEEDED/FAILED/SUSPENDED/EXCEPTION）在结果确定后写入。</li>
 *   <li>authorized 字段：STARTED 时为 false（尚未经过 ACL）；
 *       终态时根据结果判断——PERMISSION_DENIED 为 false，其他为 true。</li>
 *   <li>审计写入失败只记录日志，不阻塞工具执行。</li>
 * </ul>
 * <p>
 * 不记录完整参数值、Session、权限集合、工具完整结果或 stateData。
 */
public class ToolAuditInterceptor implements ToolInterceptor {

    private static final Logger auditLog = LoggerFactory.getLogger("TOOL_AUDIT");
    private static final Logger log = LoggerFactory.getLogger(ToolAuditInterceptor.class);

    private static final String PERMISSION_DENIED_CODE = AgentErrorCode.PERMISSION_DENIED.name();

    private final ToolAuditStore auditStore;
    private final ToolAuditIdGenerator auditIdGenerator;
    private final Clock clock;
    private final SensitiveValueSanitizer sanitizer;

    public ToolAuditInterceptor(
            ToolAuditStore auditStore,
            ToolAuditIdGenerator auditIdGenerator,
            Clock clock,
            SensitiveValueSanitizer sanitizer) {
        this.auditStore = Objects.requireNonNull(auditStore);
        this.auditIdGenerator = Objects.requireNonNull(auditIdGenerator);
        this.clock = Objects.requireNonNull(clock);
        this.sanitizer = Objects.requireNonNull(sanitizer);
    }

    @Override
    public int order() {
        return -500;
    }

    @Override
    public ToolResult intercept(
            ToolInvocation invocation,
            ToolExecutionChain chain) {

        String toolName = invocation.toolCall().name();
        String toolCallId = invocation.toolCall().id();
        RunContext runContext = invocation.runContext();
        String runId = runContext.runId();
        String threadId = runContext.threadId();
        String userId = runContext.userId();

        // 1. 提取参数键名（脱敏后仅保留键名）
        Map<String, Object> safeArguments = sanitizer.sanitize(invocation.toolCall().arguments());
        Set<String> argumentKeys = Set.copyOf(safeArguments.keySet());

        // 2. 生成审计 ID 和写入 STARTED 记录
        String auditId = auditIdGenerator.generate();
        Instant startedAt = clock.instant();
        Instant createdAt = startedAt;

        ToolInvocationAuditStarted started = new ToolInvocationAuditStarted(
                auditId,
                runId,
                threadId,
                userId,
                toolCallId,
                toolName,
                argumentKeys,
                false,  // authorized: 尚未经过 ACL
                ToolAuditStatus.STARTED,
                startedAt,
                createdAt
        );

        try {
            auditStore.start(started);
        } catch (Exception e) {
            // 审计失败不阻塞工具执行
            log.error("Failed to write audit STARTED record: auditId={}, toolName={}, runId={}",
                    auditId, toolName, runId, e);
        }

        // 3. 执行工具调用链
        Instant startTime = clock.instant();
        ToolResult result = null;
        Throwable caught = null;

        try {
            result = chain.proceed(invocation);
        } catch (AgentInterruptSignal e) {
            Instant endTime = clock.instant();
            long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

            // 4a. 中断信号：SUSPENDED
            ToolInvocationAuditCompletion suspendedCompletion = new ToolInvocationAuditCompletion(
                    auditId,
                    ToolAuditStatus.SUSPENDED,
                    false,   // success
                    true,    // authorized: 已通过 ACL（中断发生在 Approval 拦截器，在 ACL 之后）
                    null,    // errorCode
                    endTime,
                    durationMs
            );

            try {
                auditStore.complete(suspendedCompletion);
            } catch (Exception auditEx) {
                log.error("Failed to write audit SUSPENDED record: auditId={}, toolName={}, runId={}",
                        auditId, toolName, runId, auditEx);
            }

            // 保留结构化日志
            auditLog.info(
                    "toolName={} toolCallId={} runId={} threadId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false status=SUSPENDED authorized=true",
                    toolName, toolCallId, runId, threadId, userId,
                    argumentKeys, startTime, endTime, durationMs
            );

            throw e;

        } catch (Throwable t) {
            caught = t;
        }

        Instant endTime = clock.instant();
        long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

        if (caught != null) {
            // 4b. 未捕获异常：EXCEPTION
            ToolInvocationAuditCompletion exceptionCompletion = new ToolInvocationAuditCompletion(
                    auditId,
                    ToolAuditStatus.EXCEPTION,
                    false,   // success
                    true,    // authorized: 已通过 ACL（异常发生在 ACL 之后的拦截器/执行中）
                    AgentErrorCode.TOOL_EXECUTION_FAILED.name(),
                    endTime,
                    durationMs
            );

            try {
                auditStore.complete(exceptionCompletion);
            } catch (Exception auditEx) {
                log.error("Failed to write audit EXCEPTION record: auditId={}, toolName={}, runId={}",
                        auditId, toolName, runId, auditEx);
            }

            // 保留结构化日志
            auditLog.info(
                    "toolName={} toolCallId={} runId={} threadId={} userId={} "
                            + "argumentKeys={} startTime={} endTime={} durationMs={} "
                            + "success=false errorCode=UNCAUGHT_EXCEPTION status=EXCEPTION authorized=true",
                    toolName, toolCallId, runId, threadId, userId,
                    argumentKeys, startTime, endTime, durationMs
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

        // 4c. 正常返回：根据结果判定终态
        boolean authorized = !PERMISSION_DENIED_CODE.equals(result.errorCode());
        ToolAuditStatus terminalStatus = result.success()
                ? ToolAuditStatus.SUCCEEDED
                : ToolAuditStatus.FAILED;

        ToolInvocationAuditCompletion completion = new ToolInvocationAuditCompletion(
                auditId,
                terminalStatus,
                result.success(),
                authorized,
                result.errorCode(),
                endTime,
                durationMs
        );

        try {
            auditStore.complete(completion);
        } catch (Exception auditEx) {
            // 终态审计写入失败：保留 STARTED 记录，返回原始结果
            log.error("Failed to write audit terminal record: auditId={}, toolName={}, runId={}, status={}",
                    auditId, toolName, runId, terminalStatus, auditEx);
        }

        // 保留结构化日志
        auditLog.info(
                "toolName={} toolCallId={} runId={} threadId={} userId={} "
                        + "argumentKeys={} startTime={} endTime={} durationMs={} "
                        + "success={} errorCode={} status={} authorized={}",
                toolName, toolCallId, runId, threadId, userId,
                argumentKeys, startTime, endTime, durationMs,
                result.success(), result.errorCode(), terminalStatus, authorized
        );

        return result;
    }
}
