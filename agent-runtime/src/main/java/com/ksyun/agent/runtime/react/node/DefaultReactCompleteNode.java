package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.runtime.context.ContextMetadataHelper;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认 Complete 节点实现。
 * <p>
 * 模型已产生最终回答，构建 AgentResult 并结束。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultReactCompleteNode implements ReactCompleteNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactCompleteNode.class);

    @Override
    public Map<String, Object> apply(ReactAgentState state) throws Exception {
        // 确认 stopReason 为 MODEL_COMPLETED
        ReactStopReason stopReason = getStopReason(state);
        if (stopReason != ReactStopReason.MODEL_COMPLETED) {
            // 非预期状态，转失败
            return Map.of(
                    STOP_REASON, ReactStopReason.INVALID_STATE,
                    FAILURE_ERROR_CODE, com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    FAILURE_MESSAGE, "Complete node invoked without MODEL_COMPLETED stop reason"
            );
        }

        AgentDefinition definition = getAgentDefinition(state);
        List<AgentMessage> messages = getMessages(state);

        // 从 messages 中找到最后一个 AssistantAgentMessage
        AssistantAgentMessage lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantAgentMessage msg) {
                lastAssistant = msg;
                break;
            }
        }

        if (lastAssistant == null) {
            return Map.of(
                    STOP_REASON, ReactStopReason.INVALID_STATE,
                    FAILURE_ERROR_CODE, com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    FAILURE_MESSAGE, "No assistant message found for completion"
            );
        }

        // 确认该消息不再包含 ToolCall
        List<ToolCall> toolCalls = lastAssistant.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return Map.of(
                    STOP_REASON, ReactStopReason.INVALID_STATE,
                    FAILURE_ERROR_CODE, com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    FAILURE_MESSAGE, "Assistant message still contains tool calls"
            );
        }

        String content = lastAssistant.content();
        if (content == null || content.isBlank()) {
            return Map.of(
                    STOP_REASON, ReactStopReason.INVALID_STATE,
                    FAILURE_ERROR_CODE, com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    FAILURE_MESSAGE, "Assistant message has empty content"
            );
        }

        int iteration = getIteration(state);
        List<?> toolTraces = getToolTraces(state);

        Map<String, Object> metadata = Map.of(
                "iteration", iteration,
                "toolExecutionCount", toolTraces.size(),
                "stopReason", stopReason.name()
        );

        // 合并上下文处理追踪到 metadata
        ContextProcessingTrace trace = getLatestContextTrace(state);
        metadata = ContextMetadataHelper.mergeContextMetadata(metadata, trace);

        AgentResult result = new AgentResult(
                definition.name(),
                true,
                content,
                List.of(),
                metadata,
                null
        );

        return Map.of(FINAL_RESULT, result);
    }
}
