package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.run.RunStatus;
import org.bsc.langgraph4j.action.EdgeAction;

import static com.ksyun.agent.runtime.react.ReactNodeNames.*;
import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * execute_tools 节点后的条件路由。
 * <p>
 * 路由规则：
 * - RunStatus.SUSPENDED → SUSPEND
 * - 明确框架失败 → FAILURE
 * - 正常执行完成 → OBSERVE
 * <p>
 * 使用 LangGraph4j 原生 EdgeAction。
 */
public class ReactToolExecutionRouter implements EdgeAction<ReactAgentState> {

    @Override
    public String apply(ReactAgentState state) {
        RunStatus runStatus = getRunStatus(state);
        ReactStopReason stopReason = getStopReason(state);
        String failureMessage = getFailureMessage(state);
        AgentErrorCode failureErrorCode = getFailureErrorCode(state);

        // 审批挂起
        if (runStatus == RunStatus.SUSPENDED || stopReason == ReactStopReason.SUSPENDED) {
            return SUSPEND;
        }

        // 框架失败
        if (failureErrorCode != null
                || stopReason == ReactStopReason.TOOL_ERROR
                || stopReason == ReactStopReason.MODEL_ERROR
                || stopReason == ReactStopReason.INVALID_STATE
                || failureMessage != null) {
            return FAILURE;
        }

        // 正常完成 → 进入 Observe
        return OBSERVE;
    }
}
