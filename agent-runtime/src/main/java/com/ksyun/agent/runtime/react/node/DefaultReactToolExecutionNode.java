package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.react.ToolExecutionTrace;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import com.ksyun.agent.runtime.tool.ToolInvocationGateway;
import com.ksyun.agent.runtime.tool.approval.AgentInterruptSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 工具执行节点默认实现。
 * <p>
 * 基于 cursor + buffer 的顺序执行：
 * 1. 读取 pendingToolCalls
 * 2. 读取 cursor（默认 0）和 executionBuffer（默认空）
 * 3. 读取 PendingApproval（恢复时存在）
 * 4. 从 cursor 开始顺序执行 ToolCall
 * 5. 创建 ToolInvocation：
 *    - 首次运行：approval 为空
 *    - 恢复运行：仅当 cursor 位置 ToolCall 与审批绑定完全匹配时注入 approval
 *    - 不得把同一审批传给其他 ToolCall
 * 6. 调用 ToolInvocationGateway
 * 7. 正常 ToolResult 加入 buffer，cursor 前进
 * 8. APPROVED 后清空 pendingApproval，不保留旧 approval 用于下一轮
 * 9. REJECTED 时返回失败 ToolResult，加入 buffer，cursor 前进
 * 10. 新的 AgentInterruptSignal：再次挂起
 * 11. 全部完成后完整 buffer 写入 latestToolResults
 * <p>
 * cursor 之前的工具不重复执行。
 * 批准执行成功后，不得继续保留旧 approval 用于下一轮工具调用。
 */
public class DefaultReactToolExecutionNode implements ReactToolExecutionNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactToolExecutionNode.class);

    private final ToolInvocationGateway toolGateway;
    private final ReactCheckpointService checkpointService;
    private final Clock clock;

    public DefaultReactToolExecutionNode(ToolInvocationGateway toolGateway,
                                          ReactCheckpointService checkpointService,
                                          Clock clock) {
        this.toolGateway = toolGateway;
        this.checkpointService = checkpointService;
        this.clock = clock;
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
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR
            );
        }

        // 读取 cursor、buffer、pendingApproval
        int cursor = getToolExecutionCursor(state);
        List<ToolResult> buffer = new ArrayList<>(getToolExecutionBuffer(state));
        List<ToolExecutionTrace> traces = new ArrayList<>(getToolTraces(state));
        PendingApproval currentApproval = getPendingApproval(state);

        // 恢复时 approval 注入标志：仅注入一次给 cursor 位置
        boolean approvalInjected = false;

        // 从 cursor 开始顺序执行
        for (int i = cursor; i < pendingToolCalls.size(); i++) {
            ToolCall toolCall = pendingToolCalls.get(i);
            Instant startedAt = clock.instant();

            // 确定 ToolInvocation 的 approval 参数
            Optional<PendingApproval> approvalForCall = Optional.empty();
            if (currentApproval != null && !approvalInjected && isApprovalBoundToCall(currentApproval, toolCall, runContext)) {
                // 恢复执行：仅当 cursor 位置 ToolCall 与审批绑定完全匹配时注入
                approvalForCall = Optional.of(currentApproval);
                approvalInjected = true;
                log.info("Injecting approval for resumed tool: toolName={}, toolCallId={}, approvalId={}, status={}",
                        toolCall.name(), toolCall.id(), currentApproval.approvalId(), currentApproval.status());
            }

            ToolResult result;
            try {
                ToolInvocation invocation = new ToolInvocation(toolCall, runContext, approvalForCall);
                result = toolGateway.invoke(invocation);
            } catch (AgentInterruptSignal e) {
                // 新的审批中断信号（恢复后再次遇到危险工具）
                return handleInterrupt(state, e.getPendingApproval(), i, buffer, toolCall, runContext);
            } catch (AgentFrameworkException e) {
                log.error("Tool execution framework error: toolName={}, runId={}, errorCode={}",
                        toolCall.name(), runContext.runId(), e.getErrorCode());
                return Map.of(
                        STOP_REASON, ReactStopReason.TOOL_ERROR,
                        FAILURE_ERROR_CODE, e.getErrorCode(),
                        FAILURE_MESSAGE, e.getMessage(),
                        LATEST_TOOL_RESULTS, List.copyOf(buffer),
                        TOOL_TRACES, List.copyOf(traces)
                );
            } catch (Exception e) {
                log.error("Unexpected tool execution error: toolName={}, runId={}",
                        toolCall.name(), runContext.runId(), e);
                return Map.of(
                        STOP_REASON, ReactStopReason.TOOL_ERROR,
                        FAILURE_ERROR_CODE, AgentErrorCode.TOOL_EXECUTION_FAILED,
                        FAILURE_MESSAGE, "Tool execution failed",
                        LATEST_TOOL_RESULTS, List.copyOf(buffer),
                        TOOL_TRACES, List.copyOf(traces)
                );
            }

            Instant finishedAt = clock.instant();
            buffer.add(result);
            traces.add(new ToolExecutionTrace(
                    toolCall.id(),
                    toolCall.name(),
                    result.success(),
                    result.errorCode(),
                    finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                    startedAt,
                    finishedAt
            ));

            // APPROVED 后清空 pendingApproval，不保留旧 approval 用于下一轮
            if (currentApproval != null && approvalForCall.isPresent()) {
                currentApproval = null; // 清空，后续 ToolCall 不会获得此 approval
            }
        }

        // 全部完成：完整 buffer 写入 latestToolResults
        Map<String, Object> updates = new HashMap<>();
        updates.put(LATEST_TOOL_RESULTS, List.copyOf(buffer));
        updates.put(TOOL_TRACES, List.copyOf(traces));
        updates.put(TOOL_EXECUTION_BUFFER, List.of());
        updates.put(TOOL_EXECUTION_CURSOR, 0);
        updates.put(PENDING_APPROVAL, null);
        updates.put(CHECKPOINT_ID, null);
        updates.put(RUN_STATUS, null);
        return updates;
    }

    /**
     * 判断审批是否绑定到当前 ToolCall。
     * <p>
     * 必须验证：runId、threadId、userId、toolCallId、operationName/toolName、operationFingerprint。
     * 不匹配时不注入审批（ToolApprovalInterceptor会再次校验并拒绝执行）。
     * 不把同一审批传给其他 ToolCall。
     */
    private boolean isApprovalBoundToCall(PendingApproval approval, ToolCall toolCall, RunContext runContext) {
        if (approval == null || approval.payload() == null) {
            return false;
        }
        // toolCallId 严格匹配
        if (approval.payload().toolCallId() == null
                || !approval.payload().toolCallId().equals(toolCall.id())) {
            return false;
        }
        // operationName/toolName 严格匹配
        if (!approval.payload().operationName().equals(toolCall.name())) {
            return false;
        }
        // runId 匹配
        if (!approval.payload().runId().equals(runContext.runId())) {
            log.warn("Approval runId mismatch: approvalRunId={}, stateRunId={}",
                    approval.payload().runId(), runContext.runId());
            return false;
        }
        // threadId 匹配
        if (!approval.payload().threadId().equals(runContext.threadId())) {
            log.warn("Approval threadId mismatch: approvalThreadId={}, stateThreadId={}",
                    approval.payload().threadId(), runContext.threadId());
            return false;
        }
        // userId 匹配
        if (!approval.payload().userId().equals(runContext.userId())) {
            log.warn("Approval userId mismatch: approvalUserId={}, stateUserId={}",
                    approval.payload().userId(), runContext.userId());
            return false;
        }
        // operationFingerprint 非空（TOOL审批必须有指纹）
        if (approval.payload().operationFingerprint() == null
                || approval.payload().operationFingerprint().isBlank()) {
            log.warn("Approval operationFingerprint is empty for TOOL approval");
            return false;
        }
        return true;
    }

    /**
     * 处理审批中断信号。
     * <p>
     * - 不转成 ToolResult
     * - cursor 保持当前下标
     * - buffer 保留
     * - 保存 Checkpoint
     * - 写入 pendingApproval、checkpointId、RunStatus.SUSPENDED
     * - 不增加 iteration
     * - 不删除 pendingToolCalls
     */
    private Map<String, Object> handleInterrupt(ReactAgentState state,
                                                  PendingApproval approval,
                                                  int cursor,
                                                  List<ToolResult> buffer,
                                                  ToolCall interruptedToolCall,
                                                  RunContext runContext) {
        log.info("Tool execution suspended for approval: toolName={}, runId={}, cursor={}, bufferSize={}",
                interruptedToolCall.name(), runContext.runId(), cursor, buffer.size());

        // 保存 Checkpoint（支持首次挂起和再次挂起）
        AgentCheckpoint checkpoint;
        try {
            checkpoint = checkpointService.suspend(
                    state,
                    "execute_tools",
                    approval,
                    cursor,
                    buffer
            );
        } catch (Exception e) {
            log.error("Failed to save checkpoint during suspension: runId={}", runContext.runId(), e);
            return Map.of(
                    STOP_REASON, ReactStopReason.TOOL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    FAILURE_MESSAGE, "Failed to save checkpoint during suspension",
                    LATEST_TOOL_RESULTS, List.copyOf(buffer)
            );
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(STOP_REASON, ReactStopReason.SUSPENDED);
        updates.put(FAILURE_ERROR_CODE, AgentErrorCode.APPROVAL_REQUIRED);
        updates.put(FAILURE_MESSAGE, "Tool '" + interruptedToolCall.name() + "' requires manual approval");
        updates.put(PENDING_APPROVAL, approval);
        updates.put(CHECKPOINT_ID, checkpoint.checkpointId());
        updates.put(TOOL_EXECUTION_CURSOR, cursor);
        updates.put(TOOL_EXECUTION_BUFFER, List.copyOf(buffer));
        updates.put(RUN_STATUS, RunStatus.SUSPENDED);
        return updates;
    }
}
