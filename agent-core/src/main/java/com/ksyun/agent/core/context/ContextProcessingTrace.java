package com.ksyun.agent.core.context;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文处理安全统计追踪，不可变值对象。
 * <p>
 * 只保存安全统计信息，不保存消息正文、摘要正文、工具参数、
 * userId、sessionId、roles、permissions、异常对象和Spring AI类型。
 * <p>
 * 可通过 ContextProcessingResult 映射生成。
 * 字段值必须来自真实处理结果，不能重新猜测。
 * 集合不可变且去重，不使用自由字符串 Map 替代。
 */
public record ContextProcessingTrace(
        int originalMessageCount,
        int processedMessageCount,
        int originalTokenCount,
        int processedTokenCount,
        int effectiveMessageBudget,
        boolean messageCountTrimmed,
        boolean tokenTrimmed,
        boolean summaryTriggered,
        boolean summaryApplied,
        int summarizedMessageCount,
        int summarySourceTokenCount,
        int summaryTokenCount,
        boolean withinBudget,
        Set<ContextTrimDiagnostic> diagnostics,
        Instant processedAt
) {

    public ContextProcessingTrace {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        Objects.requireNonNull(processedAt, "processedAt must not be null");
        diagnostics = Collections.unmodifiableSet(new LinkedHashSet<>(diagnostics));
        if (originalMessageCount < 0) {
            throw new IllegalArgumentException("originalMessageCount must be >= 0, got: " + originalMessageCount);
        }
        if (processedMessageCount < 0) {
            throw new IllegalArgumentException("processedMessageCount must be >= 0, got: " + processedMessageCount);
        }
        if (originalTokenCount < 0) {
            throw new IllegalArgumentException("originalTokenCount must be >= 0, got: " + originalTokenCount);
        }
        if (processedTokenCount < 0) {
            throw new IllegalArgumentException("processedTokenCount must be >= 0, got: " + processedTokenCount);
        }
        if (effectiveMessageBudget < 0) {
            throw new IllegalArgumentException("effectiveMessageBudget must be >= 0, got: " + effectiveMessageBudget);
        }
        if (summarizedMessageCount < 0) {
            throw new IllegalArgumentException("summarizedMessageCount must be >= 0, got: " + summarizedMessageCount);
        }
        if (summarySourceTokenCount < 0) {
            throw new IllegalArgumentException("summarySourceTokenCount must be >= 0, got: " + summarySourceTokenCount);
        }
        if (summaryTokenCount < 0) {
            throw new IllegalArgumentException("summaryTokenCount must be >= 0, got: " + summaryTokenCount);
        }
    }

    /**
     * 从 ContextProcessingResult 映射生成 Trace。
     *
     * @param result    真实处理结果
     * @param processedAt 处理时间
     * @return 安全统计追踪
     */
    public static ContextProcessingTrace from(ContextProcessingResult result, Instant processedAt) {
        Objects.requireNonNull(result, "result must not be null");
        return new ContextProcessingTrace(
                result.originalMessageCount(),
                result.processedMessageCount(),
                (int) Math.min(result.originalTokenCount(), Integer.MAX_VALUE),
                (int) Math.min(result.processedTokenCount(), Integer.MAX_VALUE),
                result.effectiveMessageBudget(),
                result.messageCountTrimmed(),
                result.tokenTrimmed(),
                result.summaryTriggered(),
                result.summaryApplied(),
                result.summarizedMessageCount(),
                result.summarySourceTokenCount(),
                result.summaryTokenCount(),
                result.withinBudget(),
                result.diagnostics(),
                processedAt
        );
    }
}
