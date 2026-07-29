package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.message.AgentMessage;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 上下文窗口更新结果，不可变。
 * <p>
 * 约束：
 * - snapshot 不能为空
 * - modelMessages 与 snapshot.windowMessages 一致
 * - modelMessages 为不可变列表
 * - trace 与 snapshot.latestTrace 一致
 * - 不得返回完整历史
 * - 不得依赖 Spring
 */
public record ContextWindowUpdate(
        ContextWindowSnapshot snapshot,
        List<AgentMessage> modelMessages,
        ContextProcessingTrace trace
) {

    public ContextWindowUpdate {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(trace, "trace must not be null");
        modelMessages = List.copyOf(snapshot.windowMessages());
    }
}
