package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 摘要源选择结果，不可变。
 * <p>
 * 约束：
 * - sourceMessages 保持原始顺序，不可变
 * - retainedMessages 保持原始顺序，不可变
 * - existingSummary 使用 Optional
 * - sourceMessageCount 为 sourceMessages 的大小
 * - sourceTokenCount 由 TokenCounter 计算
 * - firstSourceIndex 基于原始消息索引
 * - insertionIndex 基于原始消息索引
 * - skipReason 区分"没有源"和"源太少"
 */
public record ContextSummarySelection(
        List<AgentMessage> sourceMessages,
        List<AgentMessage> retainedMessages,
        Optional<SummaryAgentMessage> existingSummary,
        int sourceMessageCount,
        int sourceTokenCount,
        int sourceGroupCount,
        int firstSourceIndex,
        int insertionIndex,
        ContextSummarySelector.SkipReason skipReason
) {

    public ContextSummarySelection {
        Objects.requireNonNull(sourceMessages, "sourceMessages must not be null");
        Objects.requireNonNull(retainedMessages, "retainedMessages must not be null");
        Objects.requireNonNull(existingSummary, "existingSummary must not be null");
        Objects.requireNonNull(skipReason, "skipReason must not be null");
        sourceMessages = List.copyOf(sourceMessages);
        retainedMessages = List.copyOf(retainedMessages);
        if (sourceMessageCount < 0) {
            throw new IllegalArgumentException(
                    "sourceMessageCount must be >= 0, got: " + sourceMessageCount);
        }
        if (sourceTokenCount < 0) {
            throw new IllegalArgumentException(
                    "sourceTokenCount must be >= 0, got: " + sourceTokenCount);
        }
        if (sourceGroupCount < 0) {
            throw new IllegalArgumentException(
                    "sourceGroupCount must be >= 0, got: " + sourceGroupCount);
        }
    }

    /**
     * 判断是否存在可摘要的源消息。
     */
    public boolean hasSource() {
        return !sourceMessages.isEmpty();
    }

    /**
     * 判断是否因源 Token 太少而跳过。
     */
    public boolean isSourceTooSmall() {
        return skipReason == ContextSummarySelector.SkipReason.SOURCE_TOO_SMALL;
    }

    /**
     * 判断是否因没有可摘要源而跳过。
     */
    public boolean isNoSource() {
        return skipReason == ContextSummarySelector.SkipReason.NO_SOURCE && !hasSource();
    }
}
