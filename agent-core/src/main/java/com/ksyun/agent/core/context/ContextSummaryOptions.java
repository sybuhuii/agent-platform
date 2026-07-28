package com.ksyun.agent.core.context;

import java.util.Objects;

/**
 * 上下文摘要选项，不可变值对象。
 * <p>
 * 由框架配置构造，不直接暴露给 HTTP 客户端。
 * <p>
 * 约束：
 * - summaryTriggerRatio 必须 > 0 且 < 1
 * - summaryMinSourceTokens 必须 > 0
 * - summaryMaxTokens 必须 > 0
 * - summaryRecentGroupsToPreserve 必须 >= 1
 * - 摘要关闭时仍允许普通消息和 Token 裁剪
 * - 不包含模型客户端
 * - 不使用自由 Map
 */
public record ContextSummaryOptions(
        boolean summaryEnabled,
        double summaryTriggerRatio,
        int summaryMinSourceTokens,
        int summaryMaxTokens,
        int summaryRecentGroupsToPreserve
) {

    public ContextSummaryOptions {
        if (summaryEnabled) {
            if (summaryTriggerRatio <= 0 || summaryTriggerRatio >= 1) {
                throw new IllegalArgumentException(
                        "summaryTriggerRatio must be > 0 and < 1, got: " + summaryTriggerRatio);
            }
            if (summaryMinSourceTokens <= 0) {
                throw new IllegalArgumentException(
                        "summaryMinSourceTokens must be > 0, got: " + summaryMinSourceTokens);
            }
            if (summaryMaxTokens <= 0) {
                throw new IllegalArgumentException(
                        "summaryMaxTokens must be > 0, got: " + summaryMaxTokens);
            }
            if (summaryRecentGroupsToPreserve < 1) {
                throw new IllegalArgumentException(
                        "summaryRecentGroupsToPreserve must be >= 1, got: " + summaryRecentGroupsToPreserve);
            }
        }
    }

    /**
     * 创建摘要关闭的默认选项。
     */
    public static ContextSummaryOptions disabled() {
        return new ContextSummaryOptions(false, 0.80, 512, 512, 4);
    }

    /**
     * 创建摘要开启的默认选项。
     */
    public static ContextSummaryOptions enabled() {
        return new ContextSummaryOptions(true, 0.80, 512, 512, 4);
    }
}
