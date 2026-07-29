package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 统一上下文处理结果，不可变。
 * <p>
 * 约束：
 * - processedMessages 不可变
 * - withinBudget 必须反映最终真实计数
 * - removedMessageCount 按初始输入和最终输出计算
 * - originalTokenCount 基于初始消息
 * - processedTokenCount 基于最终消息
 * - 不得返回中间可变集合
 * - 不得包含完整 Prompt 字符串
 * - 不依赖 Spring
 */
public record ContextProcessingResult(
        List<AgentMessage> processedMessages,
        int originalMessageCount,
        int processedMessageCount,
        int removedMessageCount,
        long originalTokenCount,
        long processedTokenCount,
        int effectiveMessageBudget,
        boolean messageCountTrimmed,
        boolean tokenTrimmed,
        boolean summaryApplied,
        boolean summaryTriggered,
        int summarizedMessageCount,
        int summarySourceTokenCount,
        int summaryTokenCount,
        boolean existingSummaryReplaced,
        boolean withinBudget,
        Set<ContextTrimDiagnostic> diagnostics
) {

    public ContextProcessingResult {
        Objects.requireNonNull(processedMessages, "processedMessages must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        processedMessages = List.copyOf(processedMessages);
        diagnostics = Set.copyOf(new LinkedHashSet<>(diagnostics));
        if (removedMessageCount < 0) {
            throw new IllegalArgumentException("removedMessageCount must be >= 0, got: " + removedMessageCount);
        }
    }

    /**
     * 创建无裁剪的处理结果。
     */
    public static ContextProcessingResult noProcessing(
            List<AgentMessage> messages,
            long tokenCount,
            int effectiveMessageBudget,
            Set<ContextTrimDiagnostic> diagnostics) {
        Objects.requireNonNull(messages, "messages must not be null");
        return new ContextProcessingResult(
                messages,
                messages.size(),
                messages.size(),
                0,
                tokenCount,
                tokenCount,
                effectiveMessageBudget,
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                false,
                true,
                diagnostics
        );
    }
}
