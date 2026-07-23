package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import org.bsc.langgraph4j.action.EdgeAction;

import java.util.List;

import static com.ksyun.agent.runtime.supervisor.SupervisorNodeNames.*;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * Supervisor 路由器，根据当前状态决定 supervisor_reason 后的路由目标。
 * <p>
 * 纯 Java 实现，不调用模型、不执行 Agent、不修改 State。
 * 实现 LangGraph4j {@link EdgeAction}，由 GraphFactory 通过 edge_async 注册。
 * <p>
 * 路由优先级：
 * 1. 存在 failureErrorCode，或 stopReason 属于 MODEL_ERROR/AGENT_ERROR/INVALID_STATE → FAIL
 * 2. decision.action=FINISH 且 pendingTasks 为空 → COMPLETE
 *    （即使 iteration 刚好达到 maxIterations，也允许正常完成）
 * 3. decision.action=DISPATCH 且 pendingTasks 非空且 iteration >= maxIterations → MAX_ITERATIONS
 * 4. decision.action=DISPATCH 且 pendingTasks 非空 → DISPATCH
 * 5. iteration >= maxIterations → MAX_ITERATIONS
 * 6. 其他无法解释的状态 → FAIL
 */
public class SupervisorRouter implements EdgeAction<SupervisorAgentState> {

    @Override
    public String apply(SupervisorAgentState state) throws Exception {
        SupervisorStopReason stopReason = getStopReason(state);
        String failureMessage = getFailureMessage(state);
        AgentErrorCode failureErrorCode = getFailureErrorCode(state);

        // 优先级1：存在 failureErrorCode，或 stopReason 属于失败类，或 failureMessage 存在
        if (failureErrorCode != null || isFailureStopReason(stopReason)
                || (failureMessage != null && !failureMessage.isBlank())) {
            return FAILURE;
        }

        SupervisorDecision decision = getDecision(state);
        List<AgentTask> pendingTasks = getPendingTasks(state);
        SupervisorDefinition definition = getSupervisorDefinition(state);
        int iteration = getIteration(state);

        // 优先级2：decision.action=FINISH 且 pendingTasks 为空 → COMPLETE
        if (decision != null && decision.action() == SupervisorAction.FINISH
                && (pendingTasks == null || pendingTasks.isEmpty())) {
            return COMPLETE;
        }

        // 优先级3：decision.action=DISPATCH 且 pendingTasks 非空且 iteration >= maxIterations
        if (decision != null && decision.action() == SupervisorAction.DISPATCH
                && pendingTasks != null && !pendingTasks.isEmpty()
                && iteration >= definition.maxIterations()) {
            return MAX_ITERATIONS_FALLBACK;
        }

        // 优先级4：decision.action=DISPATCH 且 pendingTasks 非空 → DISPATCH
        if (decision != null && decision.action() == SupervisorAction.DISPATCH
                && pendingTasks != null && !pendingTasks.isEmpty()) {
            return DISPATCH_AGENTS;
        }

        // 优先级5：iteration >= maxIterations → MAX_ITERATIONS
        if (iteration >= definition.maxIterations()) {
            return MAX_ITERATIONS_FALLBACK;
        }

        // 优先级6：其他无法解释的状态 → FAIL
        return FAILURE;
    }

    private boolean isFailureStopReason(SupervisorStopReason stopReason) {
        return stopReason == SupervisorStopReason.MODEL_ERROR
                || stopReason == SupervisorStopReason.AGENT_ERROR
                || stopReason == SupervisorStopReason.INVALID_STATE;
    }
}
