package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.message.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * 上下文窗口更新结果，不可变。
 * <p>
 * 约束：
 * - snapshot 不能为空
 * - modelMessages 为不可变列表
 * - trace 与 snapshot.latestTrace 一致
 * - 不得返回完整历史
 * - 不得依赖 Spring
 * <p>
 * Phase8 Batch5 扩展：
 * - modelMessages 可以包含 MemoryContextAgentMessage（临时上下文）
 * - snapshot.windowMessages 不得包含 MemoryContextAgentMessage
 * - modelMessages 和 snapshot.windowMessages 可以不同
 */
public record ContextWindowUpdate(
        ContextWindowSnapshot snapshot,
        List<AgentMessage> modelMessages,
        ContextProcessingTrace trace
) {

    public ContextWindowUpdate {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(trace, "trace must not be null");
        modelMessages = List.copyOf(modelMessages);
    }

    /**
     * 创建不含临时上下文的更新结果（modelMessages 等于 snapshot.windowMessages）。
     */
    public ContextWindowUpdate(ContextWindowSnapshot snapshot, ContextProcessingTrace trace) {
        this(snapshot, snapshot.windowMessages(), trace);
    }
}
