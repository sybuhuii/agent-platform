package com.ksyun.agent.runtime.checkpoint.thread;

import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 线程会话状态，不可变。
 * <p>
 * 保存一次正常执行结束后的稳定会话状态。
 * 不得包含 UserSession、sessionId、roles、permissions、RunContext。
 * 不得包含 PendingApproval、pendingToolCalls、Tool 执行游标、executionBuffer。
 * 不得包含异常对象、最终 HTTP 响应。
 * 不得包含 LangGraph4j CompiledGraph、Spring AI Message、MemoryEntry。
 * 不得使用 Map 替代固定字段。
 *
 * @param executionType       执行类型
 * @param participantName     REACT_AGENT 时为 agentName，SUPERVISOR 时为 supervisorName
 * @param messages            完整会话历史，不可变
 * @param contextWindowSnapshot 上下文窗口快照，Optional
 * @param latestContextTrace  最新上下文处理轨迹，Optional
 * @param lastCompletedRunId  最后完成的运行 ID
 * @param updatedAt           更新时间
 */
public record ThreadConversationState(
        CheckpointExecutionType executionType,
        String participantName,
        List<AgentMessage> messages,
        Optional<ContextWindowSnapshot> contextWindowSnapshot,
        Optional<ContextProcessingTrace> latestContextTrace,
        String lastCompletedRunId,
        Instant updatedAt
) {

    public ThreadConversationState {
        Objects.requireNonNull(executionType, "executionType must not be null");
        Objects.requireNonNull(participantName, "participantName must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(lastCompletedRunId, "lastCompletedRunId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        participantName = participantName.trim();
        if (participantName.isBlank()) {
            throw new IllegalArgumentException("participantName must not be blank");
        }
        if (lastCompletedRunId.isBlank()) {
            throw new IllegalArgumentException("lastCompletedRunId must not be blank");
        }

        // messages 不可变，不得存在 null
        messages = List.copyOf(messages);
        for (AgentMessage msg : messages) {
            if (msg == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
        }

        // Optional 字段不允许 null（使用 Optional.empty() 代替）
        if (contextWindowSnapshot == null) {
            contextWindowSnapshot = Optional.empty();
        }
        if (latestContextTrace == null) {
            latestContextTrace = Optional.empty();
        }
    }
}
