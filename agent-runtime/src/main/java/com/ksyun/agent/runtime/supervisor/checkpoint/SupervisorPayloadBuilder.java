package com.ksyun.agent.runtime.supervisor.checkpoint;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import com.ksyun.agent.runtime.supervisor.SupervisorDecision;
import com.ksyun.agent.runtime.supervisor.SupervisorStateKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supervisor Checkpoint payload 白名单构建器。
 * <p>
 * 只从 SupervisorAgentState 中提取恢复所必需的 key，
 * 排除可从运行时重建的对象（RunContext、SupervisorDefinition、SystemAgentMessage 等）
 * 以及恢复元数据（PENDING_APPROVAL、RUN_STATUS、STOP_REASON、CHECKPOINT_ID）。
 * <p>
 * 白名单 payload key：
 * - rootTask            → AgentTask
 * - supervisorMessages  → List<AgentMessage>（过滤 SystemAgentMessage / MemoryContextAgentMessage）
 * - decision            → SupervisorDecision
 * - pendingTasks        → List<AgentTask>
 * - latestAgentResults  → List<AgentResult>
 * - agentResults        → List<AgentResult>
 * - dispatchTasks       → List<SupervisorChildExecution>
 * - suspendedChildren   → List<SupervisorChildExecution>
 * - iteration           → Integer
 * - contextWindowSnapshot → ContextWindowSnapshot（过滤内部 System/MemoryContext 消息）
 * - latestContextTrace  → ContextProcessingTrace
 * <p>
 * 排除的 key（不进入 payload）：
 * - supervisorDefinition → 恢复时由 SupervisorRegistry 按 agentName 重建
 * - runContext           → 恢复时由当前 UserSession 重建
 * - finalResult          → 恢复时不需要
 * - stopReason           → 恢复时置 null
 * - failureMessage       → 恢复时置 null
 * - failureErrorCode     → 恢复时置 null
 * - runStatus            → 恢复时设为 RUNNING
 * - checkpointId         → 来自 Checkpoint 顶层
 * - latestMemoryContextTrace → 下次 Reason 时重新注入
 */
final class SupervisorPayloadBuilder {

    private SupervisorPayloadBuilder() {
    }

    /**
     * 从 SupervisorAgentState 提取白名单 payload。
     *
     * @param state 当前执行状态
     * @return 不可变白名单 map
     */
    static Map<String, Object> buildWhitelistPayload(SupervisorAgentState state) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // rootTask — 恢复必须
        putIfPresent(payload, SupervisorStateKeys.ROOT_TASK,
                state.<Object>value(SupervisorStateKeys.ROOT_TASK).orElse(null));

        // supervisorMessages — 过滤掉 SystemAgentMessage 和 MemoryContextAgentMessage
        List<AgentMessage> rawMessages =
                state.<List<AgentMessage>>value(SupervisorStateKeys.SUPERVISOR_MESSAGES).orElse(List.of());
        payload.put(SupervisorStateKeys.SUPERVISOR_MESSAGES, List.copyOf(filterSafeMessages(rawMessages)));

        // decision
        putIfPresent(payload, SupervisorStateKeys.DECISION,
                state.<Object>value(SupervisorStateKeys.DECISION).orElse(null));

        // pendingTasks
        List<AgentTask> pendingTasks =
                state.<List<AgentTask>>value(SupervisorStateKeys.PENDING_TASKS).orElse(List.of());
        payload.put(SupervisorStateKeys.PENDING_TASKS, List.copyOf(pendingTasks));

        // latestAgentResults
        List<AgentResult> latestResults =
                state.<List<AgentResult>>value(SupervisorStateKeys.LATEST_AGENT_RESULTS).orElse(List.of());
        payload.put(SupervisorStateKeys.LATEST_AGENT_RESULTS, List.copyOf(latestResults));

        // agentResults
        List<AgentResult> agentResults =
                state.<List<AgentResult>>value(SupervisorStateKeys.AGENT_RESULTS).orElse(List.of());
        payload.put(SupervisorStateKeys.AGENT_RESULTS, List.copyOf(agentResults));

        // iteration
        payload.put(SupervisorStateKeys.ITERATION,
                state.<Integer>value(SupervisorStateKeys.ITERATION).orElse(0));

        // contextWindowSnapshot — 过滤内部 System/MemoryContext 消息并调整 consumedCount
        ContextWindowSnapshot snapshot =
                state.<ContextWindowSnapshot>value(SupervisorStateKeys.CONTEXT_WINDOW_SNAPSHOT).orElse(null);
        if (snapshot != null) {
            payload.put(SupervisorStateKeys.CONTEXT_WINDOW_SNAPSHOT, filterSnapshot(snapshot));
        }

        // latestContextTrace — 仅包含计数/布尔值，安全
        putIfPresent(payload, SupervisorStateKeys.LATEST_CONTEXT_TRACE,
                state.<Object>value(SupervisorStateKeys.LATEST_CONTEXT_TRACE).orElse(null));

        return Collections.unmodifiableMap(payload);
    }

    /**
     * 过滤消息列表，只保留恢复安全的类型：
     * UserAgentMessage、AssistantAgentMessage、ToolAgentMessage、SummaryAgentMessage。
     * <p>
     * 排除：SystemAgentMessage（恢复时由 Supervisor Reason 重建）、
     * MemoryContextAgentMessage（下次 Reason 重新注入）。
     */
    static List<AgentMessage> filterSafeMessages(List<AgentMessage> messages) {
        List<AgentMessage> filtered = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if (isSafeMessage(msg)) {
                filtered.add(msg);
            }
        }
        return filtered;
    }

    /**
     * 过滤 ContextWindowSnapshot，移除其中的 SystemAgentMessage 和 MemoryContextAgentMessage，
     * 并相应调整 consumedHistoryMessageCount。
     */
    static ContextWindowSnapshot filterSnapshot(ContextWindowSnapshot snapshot) {
        List<AgentMessage> filtered = filterSafeMessages(snapshot.windowMessages());

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

        int adjustedConsumed = Math.max(0, consumed - removedInConsumed);

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
