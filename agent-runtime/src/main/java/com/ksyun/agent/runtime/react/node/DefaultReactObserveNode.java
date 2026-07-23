package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.ToolAgentMessage;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认 Observe 节点实现。
 * <p>
 * 将工具执行结果转换为观察消息，准备下一轮 Reason。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultReactObserveNode implements ReactObserveNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactObserveNode.class);

    @Override
    public Map<String, Object> apply(ReactAgentState state) throws Exception {
        List<ToolCall> pendingToolCalls = getPendingToolCalls(state);
        List<ToolResult> latestToolResults = getLatestToolResults(state);

        // 校验配对一致性
        if (pendingToolCalls == null || latestToolResults == null
                || pendingToolCalls.size() != latestToolResults.size()) {
            log.error("Observe node state mismatch: pendingToolCalls={}, latestToolResults={}",
                    pendingToolCalls != null ? pendingToolCalls.size() : "null",
                    latestToolResults != null ? latestToolResults.size() : "null");
            return Map.of(
                    STOP_REASON, ReactStopReason.INVALID_STATE,
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    FAILURE_MESSAGE, "Tool calls and results mismatch"
            );
        }

        // 为每组 ToolCall 和 ToolResult 构造 ToolAgentMessage
        List<AgentMessage> observeMessages = new ArrayList<>();
        for (int i = 0; i < pendingToolCalls.size(); i++) {
            ToolCall toolCall = pendingToolCalls.get(i);
            ToolResult toolResult = latestToolResults.get(i);

            observeMessages.add(new ToolAgentMessage(
                    toolCall.id(),
                    toolCall.name(),
                    toolResult.content(),
                    !toolResult.success()
            ));
        }

        // 追加观察消息到 messages，清空 pendingToolCalls 和 latestToolResults
        return Map.of(
                MESSAGES, observeMessages,
                PENDING_TOOL_CALLS, List.of(),
                LATEST_TOOL_RESULTS, List.of()
        );
    }
}
