package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.message.AgentMessage;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 上下文窗口快照，不可变。
 * <p>
 * 只保存当前模型调用需要携带的压缩窗口。
 * <p>
 * 约束：
 * - windowMessages 为不可变 List<AgentMessage>
 * - consumedHistoryMessageCount >= 0，表示完整历史中已有多少条消息被吸收到当前窗口
 * - processingSequence 初始为 1，每次成功处理后加 1
 * - latestTrace 使用 ContextProcessingTrace
 * - updatedAt 不能为空
 * - 不得保存完整历史副本
 * - 不得保存 UserSession、RunContext、ModelRequest、ModelResponse、Spring AI 类型
 * - 不得使用 Map 替代固定字段
 * - 不得使用可变集合
 * - 不得跨请求静态缓存
 */
public record ContextWindowSnapshot(
        List<AgentMessage> windowMessages,
        int consumedHistoryMessageCount,
        int processingSequence,
        ContextProcessingTrace latestTrace,
        Instant updatedAt
) {

    public ContextWindowSnapshot {
        Objects.requireNonNull(windowMessages, "windowMessages must not be null");
        Objects.requireNonNull(latestTrace, "latestTrace must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        windowMessages = Collections.unmodifiableList(windowMessages);
        if (consumedHistoryMessageCount < 0) {
            throw new IllegalArgumentException(
                    "consumedHistoryMessageCount must be >= 0, got: " + consumedHistoryMessageCount);
        }
        if (processingSequence < 1) {
            throw new IllegalArgumentException(
                    "processingSequence must be >= 1, got: " + processingSequence);
        }
    }
}
