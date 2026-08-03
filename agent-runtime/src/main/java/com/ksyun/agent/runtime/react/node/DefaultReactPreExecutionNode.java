package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;

import java.util.Map;

/** 默认的首次执行前节点，不改变运行状态。 */
public final class DefaultReactPreExecutionNode implements ReactPreExecutionNode {

    @Override
    public Map<String, Object> apply(ReactAgentState state) {
        return Map.of();
    }
}
