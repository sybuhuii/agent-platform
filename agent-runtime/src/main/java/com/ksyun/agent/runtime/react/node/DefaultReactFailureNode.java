package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.runtime.context.ContextMetadataHelper;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认失败节点实现。
 * <p>
 * 根据状态中的错误信息构造失败 AgentResult。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultReactFailureNode implements ReactFailureNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactFailureNode.class);

    @Override
    public Map<String, Object> apply(ReactAgentState state) throws Exception {
        AgentDefinition definition = getAgentDefinition(state);

        AgentErrorCode errorCode = getFailureErrorCode(state);
        if (errorCode == null) {
            errorCode = AgentErrorCode.INTERNAL_ERROR;
        }

        String failureMessage = getFailureMessage(state);
        if (failureMessage == null || failureMessage.isBlank()) {
            failureMessage = "Agent execution failed due to an internal error";
        }

        String mappedErrorCode = mapErrorCode(errorCode);

        // 合并上下文处理追踪到 metadata（框架失败前可能已存在 Trace）
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
            case TOOL_EXECUTION_FAILED -> AgentErrorCode.TOOL_EXECUTION_FAILED.name();
            case TOOL_NOT_FOUND -> AgentErrorCode.TOOL_NOT_FOUND.name();
            case TOOL_ACCESS_DENIED -> AgentErrorCode.TOOL_ACCESS_DENIED.name();
            case MAX_ITERATIONS_REACHED -> AgentErrorCode.MAX_ITERATIONS_REACHED.name();
            case INTERNAL_ERROR -> AgentErrorCode.INTERNAL_ERROR.name();
            default -> AgentErrorCode.INTERNAL_ERROR.name();
        };
    }
}
