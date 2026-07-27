package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.ToolCall;
import org.bsc.langgraph4j.action.EdgeAction;

import java.util.List;

import static com.ksyun.agent.runtime.react.ReactNodeNames.*;
import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 路由器。
 */
public class ReactRouter implements EdgeAction<ReactAgentState> {

    @Override
    public String apply(ReactAgentState state) {
        ReactStopReason stopReason = getStopReason(state);
        String failureMessage = getFailureMessage(state);
        AgentErrorCode failureErrorCode = getFailureErrorCode(state);
        List<ToolCall> pendingToolCalls = getPendingToolCalls(state);
        AgentDefinition definition = getAgentDefinition(state);
        Integer iteration = getIteration(state);

        // 1. 审批中断
        if (stopReason == ReactStopReason.SUSPENDED) {
            return SUSPEND;
        }

        // 2. 失败状态
        if (failureErrorCode != null || isFailureStopReason(stopReason) || failureMessage != null) {
            return FAILURE;
        }

        // 3. 模型完成
        if (stopReason == ReactStopReason.MODEL_COMPLETED && (pendingToolCalls == null || pendingToolCalls.isEmpty())) {
            return COMPLETE;
        }

        // 4. 有工具调用
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            if (definition != null && iteration != null && iteration >= definition.maxIterations()) {
                return MAX_ITERATIONS_FALLBACK;
            }
            return EXECUTE_TOOLS;
        }

        // 5. 无工具调用但达到最大迭代
        if (definition != null && iteration != null && iteration >= definition.maxIterations()) {
            return MAX_ITERATIONS_FALLBACK;
        }

        return FAILURE;
    }

    private boolean isFailureStopReason(ReactStopReason stopReason) {
        return stopReason == ReactStopReason.MODEL_ERROR
                || stopReason == ReactStopReason.TOOL_ERROR
                || stopReason == ReactStopReason.INVALID_STATE;
    }
}
