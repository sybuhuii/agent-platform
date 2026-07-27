package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.react.ToolExecutionTrace;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 工具执行节点默认实现。
 * <p>
 * 逐个调用 ToolInvocationGateway 执行 pendingToolCalls，
 * 收集 ToolResult 和 ToolExecutionTrace。
 * <p>
 * 中断信号处理：
 * - APPROVAL_REQUIRED 异常 → STOP_REASON=SUSPENDED（暂停等待审批）
 * - 其他 AgentFrameworkException → STOP_REASON=TOOL_ERROR
 * - 通用 Exception → STOP_REASON=TOOL_ERROR + TOOL_EXECUTION_FAILED
 */
public class DefaultReactToolExecutionNode implements ReactToolExecutionNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactToolExecutionNode.class);

    private final ToolInvocationGateway toolGateway;

    public DefaultReactToolExecutionNode(ToolInvocationGateway toolGateway) {
        this.toolGateway = toolGateway;
    }

    @Override
    public Map<String, Object> apply(ReactAgentState state) {
        List<ToolCall> pendingToolCalls = getPendingToolCalls(state);
        RunContext runContext = getRunContext(state);

        if (pendingToolCalls == null || pendingToolCalls.isEmpty()) {
            log.warn("No pending tool calls to execute");
            return Map.of(
                    STOP_REASON, ReactStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "No pending tool calls to execute",
                    FAILURE_ERROR_CODE, AgentErrorCode.INVALID_STATE
            );
        }

        List<ToolResult> results = new ArrayList<>();
        List<ToolExecutionTrace> traces = new ArrayList<>();

        for (ToolCall toolCall : pendingToolCalls) {
            Instant startedAt = Instant.now();
            ToolResult result;
            try {
                result = toolGateway.invoke(new ToolInvocation(toolCall, runContext));
            } catch (AgentFrameworkException e) {
                // 审批中断信号：识别 APPROVAL_REQUIRED，设置 SUSPENDED
                if (e.getErrorCode() == AgentErrorCode.APPROVAL_REQUIRED) {
                    log.info("Tool execution suspended for approval: toolName={}, runId={}",
                            toolCall.name(), runContext.runId());
                    return Map.of(
                            STOP_REASON, ReactStopReason.SUSPENDED,
                            FAILURE_ERROR_CODE, AgentErrorCode.APPROVAL_REQUIRED,
                            FAILURE_MESSAGE, e.getMessage(),
                            LATEST_TOOL_RESULTS, List.copyOf(results)
                    );
                }
                // 其他框架内部异常，无法形成有效 ToolResult
                log.error("Tool execution framework error: toolName={}, runId={}, errorCode={}",
                        toolCall.name(), runContext.runId(), e.getErrorCode());
                return Map.of(
                        STOP_REASON, ReactStopReason.TOOL_ERROR,
                        FAILURE_ERROR_CODE, e.getErrorCode(),
                        FAILURE_MESSAGE, e.getMessage(),
                        LATEST_TOOL_RESULTS, List.copyOf(results)
                );
            } catch (Exception e) {
                log.error("Unexpected tool execution error: toolName={}, runId={}",
                        toolCall.name(), runContext.runId(), e);
                return Map.of(
                        STOP_REASON, ReactStopReason.TOOL_ERROR,
                        FAILURE_ERROR_CODE, AgentErrorCode.TOOL_EXECUTION_FAILED,
                        FAILURE_MESSAGE, "Tool execution failed",
                        LATEST_TOOL_RESULTS, List.copyOf(results)
                );
            }

            Instant finishedAt = Instant.now();
            results.add(result);
            traces.add(new ToolExecutionTrace(
                    toolCall.id(),
                    toolCall.name(),
                    result.success(),
                    result.errorCode(),
                    finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                    startedAt,
                    finishedAt
            ));
        }

        // 覆盖 latestToolResults，追加 toolTraces，不修改 messages，不增加 iteration，不清空 pendingToolCalls
        return Map.of(
                LATEST_TOOL_RESULTS, List.copyOf(results),
                TOOL_TRACES, List.copyOf(traces)
        );
    }
}
