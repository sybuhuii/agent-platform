package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStateKeys;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checkpoint payload 白名单构建器。
 * <p>
 * 只从 ReactAgentState 中提取恢复所必需的 key，
 * 排除可从运行时重建的对象（RunContext、AgentDefinition、SystemAgentMessage 等）
 * 以及恢复元数据（PENDING_APPROVAL、RUN_STATUS、STOP_REASON、CHECKPOINT_ID）。
 * <p>
 * 白名单 payload key：
 * - task            → AgentTask
 * - messages        → List<AgentMessage>（过滤 SystemAgentMessage / MemoryContextAgentMessage）
 * - pendingToolCalls → List<ToolCall>
 * - toolExecutionCursor → Integer
 * - toolExecutionBuffer → List<ToolResult>
 * - iteration       → Integer
 * - contextWindowSnapshot → ContextWindowSnapshot（过滤内部 System/MemoryContext 消息）
 * - latestContextTrace → ContextProcessingTrace
 * - nodeResumeHandlerKey → String（NODE 类型使用，可 null）
 * - nodeResumeData → NodeResumeData（NODE 类型使用，可 null）
 * <p>
 * 排除的 key（不进入 payload）：
 * - agentDefinition → 恢复时由 AgentRegistry 按 agentName 重建
 * - runContext       → 恢复时由当前 UserSession 重建
 * - finalResult      → 恢复时不需要
 * - stopReason       → 恢复时置 null
 * - failureMessage   → 恢复时置 null
 * - failureErrorCode → 恢复时置 null
 * - runStatus        → 恢复时设为 RUNNING
 * - checkpointId     → 来自 Checkpoint 顶层
 * - pendingApproval  → 来自 Checkpoint 顶层 pendingApproval
 * - latestToolResults → 恢复时不需要（清空）
 * - toolTraces       → 恢复时不需要
 * - latestMemoryContextTrace → 下次 Reason 时重新注入
 */
final class CheckpointPayloadBuilder {

    private CheckpointPayloadBuilder() {
    }

    /**
     * 从 ReactAgentState 提取白名单 payload。
     *
     * @param state 当前执行状态
     * @return 不可变白名单 map
     */
    static Map<String, Object> buildWhitelistPayload(ReactAgentState state) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // task — 恢复必须
        putIfPresent(payload, ReactStateKeys.TASK, state.<Object>value(ReactStateKeys.TASK).orElse(null));

        // messages — 过滤掉 SystemAgentMessage 和 MemoryContextAgentMessage
        List<AgentMessage> rawMessages = state.<List<AgentMessage>>value(ReactStateKeys.MESSAGES).orElse(List.of());
        payload.put(ReactStateKeys.MESSAGES, List.copyOf(filterSafeMessages(rawMessages)));

        // pendingToolCalls
        putIfPresent(payload, ReactStateKeys.PENDING_TOOL_CALLS,
                state.<Object>value(ReactStateKeys.PENDING_TOOL_CALLS).orElse(null));

        // toolExecutionCursor
        payload.put(ReactStateKeys.TOOL_EXECUTION_CURSOR,
                state.<Integer>value(ReactStateKeys.TOOL_EXECUTION_CURSOR).orElse(0));

        // toolExecutionBuffer
        @SuppressWarnings("unchecked")
        List<com.ksyun.agent.core.tool.ToolResult> rawBuffer =
                state.<List<com.ksyun.agent.core.tool.ToolResult>>value(ReactStateKeys.TOOL_EXECUTION_BUFFER).orElse(List.of());
        payload.put(ReactStateKeys.TOOL_EXECUTION_BUFFER, List.copyOf(rawBuffer));

        // iteration
        payload.put(ReactStateKeys.ITERATION, state.<Integer>value(ReactStateKeys.ITERATION).orElse(0));

        // contextWindowSnapshot — 过滤内部 System/MemoryContext 消息并调整 consumedCount
        ContextWindowSnapshot snapshot = state.<ContextWindowSnapshot>value(ReactStateKeys.CONTEXT_WINDOW_SNAPSHOT).orElse(null);
        if (snapshot != null) {
            payload.put(ReactStateKeys.CONTEXT_WINDOW_SNAPSHOT, filterSnapshot(snapshot));
        }

        // latestContextTrace — 仅包含计数/布尔值，安全
        putIfPresent(payload, ReactStateKeys.LATEST_CONTEXT_TRACE,
                state.<Object>value(ReactStateKeys.LATEST_CONTEXT_TRACE).orElse(null));

        // nodeResumeHandlerKey（NODE 类型使用，可 null）
        putIfPresent(payload, ReactStateKeys.NODE_RESUME_HANDLER_KEY,
                state.<Object>value(ReactStateKeys.NODE_RESUME_HANDLER_KEY).orElse(null));

        // nodeResumeData（NODE 类型使用，可 null）
        putIfPresent(payload, ReactStateKeys.NODE_RESUME_DATA,
                state.<Object>value(ReactStateKeys.NODE_RESUME_DATA).orElse(null));

        return Collections.unmodifiableMap(payload);
    }

    /**
     * 过滤消息列表，只保留恢复安全的类型：
     * UserAgentMessage、AssistantAgentMessage、ToolAgentMessage、SummaryAgentMessage。
     * <p>
     * 排除：SystemAgentMessage（恢复时由 Reason 重建）、MemoryContextAgentMessage（下次 Reason 重新注入）。
     */
    static List<AgentMessage> filterSafeMessages(List<AgentMessage> messages) {
        return messages.stream()
                .filter(CheckpointPayloadBuilder::isSafeMessage)
                .toList();
    }

    /**
     * 过滤 ContextWindowSnapshot，移除其中的 SystemAgentMessage 和 MemoryContextAgentMessage，
     * 并相应调整 consumedHistoryMessageCount。
     * <p>
     * consumedHistoryMessageCount 表示"完整历史中已有多少条消息被吸收到当前窗口"。
     * 过滤掉的 System/MemoryContext 消息如果在 consumed 范围内，需要从计数中减去。
     */
    static ContextWindowSnapshot filterSnapshot(ContextWindowSnapshot snapshot) {
        List<AgentMessage> filtered = filterSafeMessages(snapshot.windowMessages());

        // 计算在 consumed 范围内被过滤掉的消息数
        int consumed = snapshot.consumedHistoryMessageCount();
        int removedInConsumed = 0;
        int count = 0;
        for (AgentMessage msg : snapshot.windowMessages()) {
            if (count >= consumed) {
                break;
            }
            if (!isSafeMessage(msg)) {
                removedInConsumed++;
            }
            count++;
        }

        int adjustedConsumed = consumed - removedInConsumed;

        return new ContextWindowSnapshot(
                filtered,
                adjustedConsumed,
                snapshot.processingSequence(),
                snapshot.latestTrace(),
                snapshot.updatedAt()
        );
    }

    private static boolean isSafeMessage(AgentMessage msg) {
        return !(msg instanceof SystemAgentMessage) && !(msg instanceof MemoryContextAgentMessage);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
