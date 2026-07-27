package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.approval.InterruptReason;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 挂起节点。
 * <p>
 * 当 STOP_REASON == SUSPENDED 时执行此节点。
 * 保存完整 Checkpoint 到 CheckpointStore。
 * 构造挂起的 AgentResult（success=false, errorCode=APPROVAL_REQUIRED）。
 */
public class DefaultReactSuspendNode implements NodeAction<ReactAgentState> {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactSuspendNode.class);

    private static final String SUSPENDED_CONTENT =
            "Agent execution suspended: a tool requires manual approval before execution. " +
            "Please review and approve or reject the pending tool call.";

    private final ReactCheckpointService checkpointService;

    public DefaultReactSuspendNode(ReactCheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @Override
    public Map<String, Object> apply(ReactAgentState state) {
        RunContext runContext = getRunContext(state);
        String failureMessage = getFailureMessage(state);
        AgentErrorCode failureErrorCode = getFailureErrorCode(state);
        List<ToolCall> pendingToolCalls = getPendingToolCalls(state);
        var definition = getAgentDefinition(state);

        if (runContext == null) {
            log.error("Suspend node invoked without RunContext");
            return Map.of(
                    FINAL_RESULT, AgentResult.failure("unknown",
                            AgentErrorCode.INTERNAL_ERROR.name(),
                            "Suspend node invoked without RunContext")
            );
        }

        // 确定触发中断的工具调用
        ToolCall suspendedToolCall = null;
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            suspendedToolCall = pendingToolCalls.get(0);
        }

        // 确定中断原因
        InterruptReason interruptReason = InterruptReason.TOOL_RISK_HIGH;
        String reason = failureMessage != null ? failureMessage : "Dangerous tool requires manual approval";

        // 保存 Checkpoint
        checkpointService.saveCheckpoint(
                runContext.runId(),
                runContext.threadId(),
                RunStatus.INTERRUPTED,
                state.data(),
                0,
                suspendedToolCall,
                interruptReason,
                reason,
                runContext
        );

        // 构造挂起的 AgentResult
        String agentName = definition != null ? definition.name() : "unknown";

        AgentResult suspendedResult = AgentResult.failure(
                agentName,
                AgentErrorCode.APPROVAL_REQUIRED.name(),
                SUSPENDED_CONTENT
        );

        log.info("Agent suspended: agentName={}, runId={}, toolName={}, approvalRequired",
                agentName, runContext.runId(),
                suspendedToolCall != null ? suspendedToolCall.name() : "unknown");

        return Map.of(
                FINAL_RESULT, suspendedResult,
                STOP_REASON, ReactStopReason.SUSPENDED
        );
    }
}
