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
 * ReAct 路由器，根据当前状态决定 Reason 后的路由目标。
 * <p>
 * 纯 Java 实现，不调用模型、不执行工具、不修改业务数据。
 * 实现 LangGraph4j {@link EdgeAction}，由 GraphFactory 通过 edge_async 注册。
 * <p>
 * 路由优先级：
 * 1. 存在 failureErrorCode，或 stopReason 属于 MODEL_ERROR/TOOL_ERROR/INVALID_STATE → FAIL
 * 2. stopReason=MODEL_COMPLETED 且 pendingToolCalls 为空 → COMPLETE
 *    （即使 iteration 刚好等于 maxIterations，也允许正常完成）
 * 3. pendingToolCalls 非空且 iteration >= maxIterations → MAX_ITERATIONS
 *    （不得执行这些新 ToolCall）
 * 4. pendingToolCalls 非空 → EXECUTE_TOOLS
 * 5. iteration >= maxIterations → MAX_ITERATIONS
 * 6. 其他无法解释的状态 → FAIL
 */
public class ReactRouter implements EdgeAction<ReactAgentState> {

    @Override
    public String apply(ReactAgentState state) throws Exception {
        ReactStopReason stopReason = getStopReason(state);
        String failureMessage = getFailureMessage(state);
        AgentErrorCode failureErrorCode = getFailureErrorCode(state);

        // 优先级1：存在 failureErrorCode，或 stopReason 属于失败类，或 failureMessage 存在
        if (failureErrorCode != null || isFailureStopReason(stopReason)
                || (failureMessage != null && !failureMessage.isBlank())) {
            return FAILURE;
        }

        List<ToolCall> pendingToolCalls = getPendingToolCalls(state);
        AgentDefinition definition = getAgentDefinition(state);
        int iteration = getIteration(state);

        // 优先级2：stopReason=MODEL_COMPLETED 且 pendingToolCalls 为空 → COMPLETE
        if (stopReason == ReactStopReason.MODEL_COMPLETED
                && (pendingToolCalls == null || pendingToolCalls.isEmpty())) {
            return COMPLETE;
        }

        // 优先级3：pendingToolCalls 非空且 iteration >= maxIterations → MAX_ITERATIONS
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()
                && iteration >= definition.maxIterations()) {
            return MAX_ITERATIONS_FALLBACK;
        }

        // 优先级4：pendingToolCalls 非空 → EXECUTE_TOOLS
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            return EXECUTE_TOOLS;
        }

        // 优先级5：iteration >= maxIterations → MAX_ITERATIONS
        if (iteration >= definition.maxIterations()) {
            return MAX_ITERATIONS_FALLBACK;
        }

        // 优先级6：其他无法解释的状态 → FAIL
        return FAILURE;
    }

    private boolean isFailureStopReason(ReactStopReason stopReason) {
        return stopReason == ReactStopReason.MODEL_ERROR
                || stopReason == ReactStopReason.TOOL_ERROR
                || stopReason == ReactStopReason.INVALID_STATE;
    }
}
