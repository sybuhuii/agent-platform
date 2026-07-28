package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.context.ContextResultMetadataKeys;
import com.ksyun.agent.core.context.ContextTrimDiagnostic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文结果 metadata 工具类，纯 Java 实现。
 * <p>
 * 将 ContextProcessingTrace 映射到 AgentResult.metadata。
 * 只写安全统计，不写 processedMessages、摘要正文、原始历史、
 * 工具参数、Session 和权限。
 * diagnostics 转换为稳定字符串代码列表。
 * metadata 集合保持不可变。
 * <p>
 * 不存在 Trace 时不写伪造统计。
 * 合并时保持原 metadata。
 */
public final class ContextMetadataHelper {

    private ContextMetadataHelper() {
    }

    /**
     * 将上下文追踪安全统计合并到已有 metadata 中。
     * <p>
     * 不存在 Trace 时返回原始 metadata。
     * 不覆盖现有业务 metadata。
     *
     * @param existingMetadata 已有 metadata（可能为空或 null）
     * @param trace            上下文处理追踪（可能为 null）
     * @return 合并后的不可变 metadata
     */
    public static Map<String, Object> mergeContextMetadata(Map<String, Object> existingMetadata,
                                                            ContextProcessingTrace trace) {
        if (trace == null) {
            return existingMetadata != null ? existingMetadata : Map.of();
        }

        Map<String, Object> merged = new HashMap<>();
        if (existingMetadata != null) {
            merged.putAll(existingMetadata);
        }

        // 只写入不冲突的上下文统计
        putIfAbsent(merged, ContextResultMetadataKeys.ORIGINAL_MESSAGE_COUNT, trace.originalMessageCount());
        putIfAbsent(merged, ContextResultMetadataKeys.PROCESSED_MESSAGE_COUNT, trace.processedMessageCount());
        putIfAbsent(merged, ContextResultMetadataKeys.ORIGINAL_TOKEN_COUNT, trace.originalTokenCount());
        putIfAbsent(merged, ContextResultMetadataKeys.PROCESSED_TOKEN_COUNT, trace.processedTokenCount());
        putIfAbsent(merged, ContextResultMetadataKeys.EFFECTIVE_MESSAGE_BUDGET, trace.effectiveMessageBudget());
        putIfAbsent(merged, ContextResultMetadataKeys.MESSAGE_COUNT_TRIMMED, trace.messageCountTrimmed());
        putIfAbsent(merged, ContextResultMetadataKeys.TOKEN_TRIMMED, trace.tokenTrimmed());
        putIfAbsent(merged, ContextResultMetadataKeys.SUMMARY_TRIGGERED, trace.summaryTriggered());
        putIfAbsent(merged, ContextResultMetadataKeys.SUMMARY_APPLIED, trace.summaryApplied());
        putIfAbsent(merged, ContextResultMetadataKeys.WITHIN_BUDGET, trace.withinBudget());

        // diagnostics 转换为稳定字符串代码列表
        Set<ContextTrimDiagnostic> diagnostics = trace.diagnostics();
        if (diagnostics != null && !diagnostics.isEmpty()) {
            List<String> diagCodes = diagnostics.stream()
                    .map(ContextTrimDiagnostic::name)
                    .toList();
            putIfAbsent(merged, ContextResultMetadataKeys.DIAGNOSTICS, diagCodes);
        }

        return Map.copyOf(merged);
    }

    private static void putIfAbsent(Map<String, Object> map, String key, Object value) {
        if (!map.containsKey(key)) {
            map.put(key, value);
        }
    }
}
