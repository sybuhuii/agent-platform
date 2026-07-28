package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.context.ContextMetadataHelper;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;

import java.util.Map;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor 失败节点实现。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultSupervisorFailureNode implements SupervisorFailureNode {

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        SupervisorDefinition definition = getSupervisorDefinition(state);

        AgentErrorCode errorCode = getFailureErrorCode(state);
        if (errorCode == null) {
            errorCode = AgentErrorCode.INTERNAL_ERROR;
        }

        String failureMessage = getFailureMessage(state);
        if (failureMessage == null || failureMessage.isBlank()) {
            failureMessage = "Supervisor execution failed due to an internal error";
        }

        String mappedErrorCode = mapErrorCode(errorCode);

        // 合并 Supervisor 上下文处理追踪到 metadata
        ContextProcessingTrace trace = getLatestContextTrace(state);
        Map<String, Object> metadata = ContextMetadataHelper.mergeContextMetadata(Map.of(), trace);

        AgentResult result = new AgentResult(
                definition.name(),
                false,
                failureMessage,
                java.util.List.of(),
                metadata,
                mappedErrorCode
        );

        return Map.of(FINAL_RESULT, result);
    }

    private String mapErrorCode(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case MODEL_INVOCATION_FAILED -> AgentErrorCode.MODEL_INVOCATION_FAILED.name();
            case AGENT_NOT_FOUND -> AgentErrorCode.AGENT_NOT_FOUND.name();
            case MAX_ITERATIONS_REACHED -> AgentErrorCode.MAX_ITERATIONS_REACHED.name();
            case INTERNAL_ERROR -> AgentErrorCode.INTERNAL_ERROR.name();
            default -> AgentErrorCode.INTERNAL_ERROR.name();
        };
    }
}
