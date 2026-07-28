package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.context.ContextMetadataHelper;
import com.ksyun.agent.runtime.supervisor.SupervisorAction;
import com.ksyun.agent.runtime.supervisor.SupervisorDecision;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;

import java.util.*;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor Complete 节点实现。
 * <p>
 * Supervisor 已决定完成，构建最终 AgentResult。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultSupervisorCompleteNode implements SupervisorCompleteNode {

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        SupervisorDecision decision = getDecision(state);
        SupervisorDefinition definition = getSupervisorDefinition(state);
        List<AgentResult> agentResults = getAgentResults(state);
        int iteration = getIteration(state);

        // 确认 decision
        if (decision == null || decision.action() != SupervisorAction.FINISH) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Complete node invoked without FINISH decision"
            );
        }

        String finalAnswer = decision.finalAnswer();
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Complete node invoked with blank finalAnswer"
            );
        }

        // 从历史 agentResults 收集 evidence
        List<String> evidence = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AgentResult ar : agentResults) {
            if (ar.evidence() != null) {
                for (String ev : ar.evidence()) {
                    if (ev != null && !ev.isBlank() && seen.add(ev)) {
                        evidence.add(ev);
                        if (evidence.size() >= 20) break;
                    }
                }
            }
            if (evidence.size() >= 20) break;
        }

        // 统计
        int dispatchedTaskCount = agentResults.size();
        int successCount = (int) agentResults.stream().filter(AgentResult::success).count();
        int failedCount = dispatchedTaskCount - successCount;

        Map<String, Object> metadata = Map.of(
                "iteration", iteration,
                "dispatchedTaskCount", dispatchedTaskCount,
                "agentResultCount", agentResults.size(),
                "successfulAgentCount", successCount,
                "failedAgentCount", failedCount,
                "stopReason", SupervisorStopReason.COMPLETED.name()
        );

        // 合并 Supervisor 上下文处理追踪到 metadata
        ContextProcessingTrace trace = getLatestContextTrace(state);
        metadata = ContextMetadataHelper.mergeContextMetadata(metadata, trace);

        AgentResult result = new AgentResult(
                definition.name(),
                true,
                finalAnswer,
                List.copyOf(evidence),
                metadata,
                null
        );

        return Map.of(
                FINAL_RESULT, result,
                STOP_REASON, SupervisorStopReason.COMPLETED
        );
    }
}
