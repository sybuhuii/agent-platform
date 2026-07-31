package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.context.ContextMetadataHelper;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import com.ksyun.agent.runtime.memory.MemoryContextTrace;

import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor 最大迭代次数回退节点实现。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultSupervisorMaxIterationsNode implements SupervisorMaxIterationsNode {

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        SupervisorDefinition definition = getSupervisorDefinition(state);
        int iteration = getIteration(state);
        List<?> agentResults = getAgentResults(state);

        Map<String, Object> metadata = Map.of(
                "iteration", iteration,
                "maxIterations", definition.maxIterations(),
                "agentResultCount", agentResults.size(),
                "supervisorName", definition.name()
        );

        // 合并 Supervisor 上下文处理追踪到 metadata
        ContextProcessingTrace trace = getLatestContextTrace(state);
        metadata = ContextMetadataHelper.mergeContextMetadata(metadata, trace);

        MemoryContextTrace memoryTrace =
                getLatestMemoryContextTrace(state);
        metadata = ContextMetadataHelper.mergeMemoryMetadata(
                metadata,
                memoryTrace);

        AgentResult result = new AgentResult(
                definition.name(),
                false,
                "Supervisor已达到最大调度迭代次数，未能在限制内完成任务。",
                List.of(),
                metadata,
                AgentErrorCode.MAX_ITERATIONS_REACHED.name()
        );

        return Map.of(
                FINAL_RESULT, result,
                STOP_REASON, SupervisorStopReason.MAX_ITERATIONS_REACHED,
                PENDING_TASKS, List.of(),
                LATEST_AGENT_RESULTS, List.of(),
                RUN_STATUS, com.ksyun.agent.core.run.RunStatus.FAILED
        );
    }
}
