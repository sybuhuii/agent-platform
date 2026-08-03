package com.ksyun.agent.runtime.react;

import org.bsc.langgraph4j.action.EdgeAction;

import static com.ksyun.agent.runtime.react.ReactNodeNames.FAILURE;
import static com.ksyun.agent.runtime.react.ReactNodeNames.REASON;
import static com.ksyun.agent.runtime.react.ReactNodeNames.SUSPEND;
import static com.ksyun.agent.runtime.react.ReactStateKeys.getFailureErrorCode;
import static com.ksyun.agent.runtime.react.ReactStateKeys.getFailureMessage;
import static com.ksyun.agent.runtime.react.ReactStateKeys.getStopReason;

/** 首次执行前扩展节点的路由。 */
public final class ReactPreExecutionRouter implements EdgeAction<ReactAgentState> {

    @Override
    public String apply(ReactAgentState state) {
        ReactStopReason stopReason = getStopReason(state);
        if (stopReason == ReactStopReason.SUSPENDED) {
            return SUSPEND;
        }
        if (getFailureErrorCode(state) != null
                || getFailureMessage(state) != null
                || stopReason == ReactStopReason.INVALID_STATE) {
            return FAILURE;
        }
        return REASON;
    }
}
