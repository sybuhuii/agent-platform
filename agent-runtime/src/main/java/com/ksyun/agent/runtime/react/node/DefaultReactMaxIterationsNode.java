package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认最大迭代次数回退节点实现。
 * <p>
 * 构建 MAX_ITERATIONS_REACHED 的 AgentResult 并结束。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultReactMaxIterationsNode implements ReactMaxIterationsNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactMaxIterationsNode.class);

    @Override
    public Map<String, Object> apply(ReactAgentState state) throws Exception {
        AgentDefinition definition = getAgentDefinition(state);
        int iteration = getIteration(state);

        Map<String, Object> metadata = Map.of(
                "iteration", iteration,
                "maxIterations", definition.maxIterations(),
                "agentName", definition.name()
        );

        AgentResult result = new AgentResult(
                definition.name(),
                false,
                "Agent已达到最大迭代次数，未能在限制内完成任务。",
                List.of(),
                metadata,
                AgentErrorCode.MAX_ITERATIONS_REACHED.name()
        );

        // 清空未执行的 pendingToolCalls 和 latestToolResults
        return Map.of(
                FINAL_RESULT, result,
                STOP_REASON, ReactStopReason.MAX_ITERATIONS_REACHED,
                PENDING_TOOL_CALLS, List.of(),
                LATEST_TOOL_RESULTS, List.of()
        );
    }
}
