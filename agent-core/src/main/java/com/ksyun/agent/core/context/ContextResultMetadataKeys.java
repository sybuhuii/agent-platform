package com.ksyun.agent.core.context;

/**
 * 上下文结果 metadata 稳定常量。
 * <p>
 * 禁止在节点和终止节点散落字符串，统一使用此类常量。
 * 只写安全统计，不写 processedMessages、摘要正文、原始历史、
 * 工具参数、Session 和权限。
 * diagnostics 转换为稳定字符串代码列表。
 * metadata 集合保持不可变。
 */
public final class ContextResultMetadataKeys {

    private ContextResultMetadataKeys() {
    }

    public static final String ORIGINAL_MESSAGE_COUNT = "context.originalMessageCount";
    public static final String PROCESSED_MESSAGE_COUNT = "context.processedMessageCount";
    public static final String ORIGINAL_TOKEN_COUNT = "context.originalTokenCount";
    public static final String PROCESSED_TOKEN_COUNT = "context.processedTokenCount";
    public static final String EFFECTIVE_MESSAGE_BUDGET = "context.effectiveMessageBudget";
    public static final String MESSAGE_COUNT_TRIMMED = "context.messageCountTrimmed";
    public static final String TOKEN_TRIMMED = "context.tokenTrimmed";
    public static final String SUMMARY_TRIGGERED = "context.summaryTriggered";
    public static final String SUMMARY_APPLIED = "context.summaryApplied";
    public static final String WITHIN_BUDGET = "context.withinBudget";
    public static final String DIAGNOSTICS = "context.diagnostics";
}
