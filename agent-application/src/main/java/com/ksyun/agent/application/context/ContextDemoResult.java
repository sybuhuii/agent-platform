package com.ksyun.agent.application.context;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文演示结果，不可变。
 * <p>
 * 约束：
 * - 不得返回完整消息
 * - 不得返回摘要正文
 * - 不得返回完整 Prompt
 * - diagnostics 转换为稳定字符串代码列表
 */
public record ContextDemoResult(
        String runId,
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
        List<String> diagnostics,
        boolean modelInvoked,
        String modelContent,
        String modelErrorCode,
        Instant completedAt
) {

    public ContextDemoResult {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        diagnostics = Collections.unmodifiableList(diagnostics);
    }
}
