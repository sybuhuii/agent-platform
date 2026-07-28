package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;

import java.util.Collections;
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
 * - sourceTokenCount 为 sourceMessages 的 Token 数
 * - sourceGroupCount 为摘要源中原子组数量
 * - firstSourceIndex 为第一条源消息在原始列表中的下标
 * - insertionIndex 为摘要插入位置的下标
 * - 不得修改原列表
 * - 不得根据 content 文本判断消息角色
 * - 不得根据工具名称进行配对
 * - 相同输入必须得到相同选择结果
 * - 不得使用 HashSet 无序迭代决定结果
 */
public record ContextSummarySelection(
        List<AgentMessage> sourceMessages,
        List<AgentMessage> retainedMessages,
        Optional<SummaryAgentMessage> existingSummary,
        int sourceMessageCount,
        int sourceTokenCount,
        int sourceGroupCount,
        int firstSourceIndex,
        int insertionIndex
) {

    public ContextSummarySelection {
        Objects.requireNonNull(sourceMessages, "sourceMessages must not be null");
        Objects.requireNonNull(retainedMessages, "retainedMessages must not be null");
        sourceMessages = Collections.unmodifiableList(sourceMessages);
        retainedMessages = Collections.unmodifiableList(retainedMessages);
        existingSummary = existingSummary == null ? Optional.empty() : existingSummary;
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
}
