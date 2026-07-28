package com.ksyun.agent.api.dto;

import com.ksyun.agent.application.framework.FrameworkQueryService.ContextCapabilityInfo;

/**
 * 上下文管理能力响应 DTO。
 * <p>
 * 只读，不暴露 Bean 实现类完整包名、Prompt、消息或用户数据。
 * 不直接提供裁剪执行 HTTP 接口。
 */
public record ContextCapabilityResponse(
        boolean enabled,
        boolean messageCountTrimmingEnabled,
        int maxMessages,
        boolean tokenTrimmingEnabled,
        int maxContextTokens,
        int reservedOutputTokens,
        int reservedProtocolTokens,
        int safetyMarginTokens,
        int availableMessageTokens,
        String tokenCounterType,
        boolean exactTokenCount,
        String pipelineOrder,
        boolean summaryEnabled,
        boolean summaryAvailable,
        double summaryTriggerRatio,
        int summaryTriggerTokens,
        int summaryMinSourceTokens,
        int summaryMaxTokens,
        int summaryRecentGroupsToPreserve,
        boolean summaryUsesLlm,
        String summaryFailureFallback,
        // Phase7 Batch4 新增
        boolean runtimeIntegrationEnabled,
        boolean reactIntegrated,
        boolean supervisorIntegrated,
        boolean contextWindowSnapshotEnabled,
        boolean fullHistoryPreserved,
        String summaryMessageMapping,
        boolean resultMetadataEnabled,
        boolean demoAvailable
) {

    /**
     * 从 FrameworkQueryService 的 ContextCapabilityInfo 构造响应。
     */
    public static ContextCapabilityResponse from(ContextCapabilityInfo info) {
        return new ContextCapabilityResponse(
                info.enabled(),
                info.messageCountTrimmingEnabled(),
                info.maxMessages(),
                info.tokenTrimmingEnabled(),
                info.maxContextTokens(),
                info.reservedOutputTokens(),
                info.reservedProtocolTokens(),
                info.safetyMarginTokens(),
                info.availableMessageTokens(),
                info.tokenCounterType(),
                info.exactTokenCount(),
                info.pipelineOrder(),
                info.summaryEnabled(),
                info.summaryAvailable(),
                info.summaryTriggerRatio(),
                info.summaryTriggerTokens(),
                info.summaryMinSourceTokens(),
                info.summaryMaxTokens(),
                info.summaryRecentGroupsToPreserve(),
                info.summaryUsesLlm(),
                info.summaryFailureFallback(),
                info.runtimeIntegrationEnabled(),
                info.reactIntegrated(),
                info.supervisorIntegrated(),
                info.contextWindowSnapshotEnabled(),
                info.fullHistoryPreserved(),
                info.summaryMessageMapping(),
                info.resultMetadataEnabled(),
                info.demoAvailable()
        );
    }
}
