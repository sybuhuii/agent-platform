package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文裁剪结果，不可变。
 * <p>
 * 约束：
 * - retainedMessages 保持原始顺序，不可变列表
 * - 不保存完整 originalMessages（只保留统计值）
 * - removedMessageCount 不得小于 0
 * - atomicGroupOvershoot 为维护工具消息完整性而超过目标的数量
 * - diagnostics 只保存安全、稳定的诊断信息
 * - 不保存完整 Prompt 副本、RunContext、Session 或权限
 * - 不依赖 Spring
 * - 不使用 null 表达空集合
 * - tokenUtilization 使用 double 比例值
 * - 预算为 0 时 tokenUtilization 可能为 0.0
 * - withinTokenBudget 必须反映最终真实计数
 * - 不得返回超预算结果表示成功
 */
public record ContextTrimResult(
        List<AgentMessage> retainedMessages,
        int originalMessageCount,
        int retainedMessageCount,
        int removedMessageCount,
        int retainedSystemMessageCount,
        int retainedNonSystemMessageCount,
        int targetMaxMessages,
        int atomicGroupOvershoot,
        Set<ContextTrimDiagnostic> diagnostics,
        long estimatedTokensBefore,
        long estimatedTokensAfter,
        int maxContextTokens,
        int availableMessageTokens,
        int additionalReservedTokens,
        int effectiveMessageBudget,
        double tokenUtilizationBefore,
        double tokenUtilizationAfter,
        boolean withinTokenBudget
) {

    public ContextTrimResult {
        Objects.requireNonNull(retainedMessages, "retainedMessages must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        retainedMessages = List.copyOf(retainedMessages);
        diagnostics = Set.copyOf(new LinkedHashSet<>(diagnostics));
        if (removedMessageCount < 0) {
            throw new IllegalArgumentException("removedMessageCount must be >= 0, got: " + removedMessageCount);
        }
        if (atomicGroupOvershoot < 0) {
            throw new IllegalArgumentException("atomicGroupOvershoot must be >= 0, got: " + atomicGroupOvershoot);
        }
        // 验证 tokenUtilization 不含 NaN/Infinity（预算为 0 时允许 0.0）
        if (Double.isNaN(tokenUtilizationBefore) || Double.isInfinite(tokenUtilizationBefore)) {
            throw new IllegalArgumentException("tokenUtilizationBefore must not be NaN or Infinity");
        }
        if (Double.isNaN(tokenUtilizationAfter) || Double.isInfinite(tokenUtilizationAfter)) {
            throw new IllegalArgumentException("tokenUtilizationAfter must not be NaN or Infinity");
        }
    }

    /**
     * 创建无需裁剪的结果（仅消息数裁剪场景）。
     */
    public static ContextTrimResult noTrim(List<AgentMessage> original,
                                           int systemCount,
                                           int nonSystemCount,
                                           int maxMessages,
                                           long estimatedTokens) {
        Objects.requireNonNull(original, "original must not be null");
        Set<ContextTrimDiagnostic> diags = new LinkedHashSet<>();
        if (systemCount > 0) {
            diags.add(ContextTrimDiagnostic.SYSTEM_MESSAGES_PRESERVED);
        }
        diags.add(ContextTrimDiagnostic.NO_TRIMMING_REQUIRED);
        return new ContextTrimResult(
                original,
                original.size(),
                original.size(),
                0,
                systemCount,
                nonSystemCount,
                maxMessages,
                0,
                diags,
                estimatedTokens,
                estimatedTokens,
                0, 0, 0, 0,
                0.0, 0.0,
                true
        );
    }

    /**
     * 创建仅消息数裁剪的结果。
     */
    public static ContextTrimResult forMessageCount(
            List<AgentMessage> retainedMessages,
            int originalMessageCount,
            int retainedSystemCount,
            int retainedNonSystemCount,
            int maxMessages,
            int atomicGroupOvershoot,
            Set<ContextTrimDiagnostic> diagnostics,
            long tokensBefore,
            long tokensAfter) {
        return new ContextTrimResult(
                retainedMessages,
                originalMessageCount,
                retainedMessages.size(),
                originalMessageCount - retainedMessages.size(),
                retainedSystemCount,
                retainedNonSystemCount,
                maxMessages,
                atomicGroupOvershoot,
                diagnostics,
                tokensBefore,
                tokensAfter,
                0, 0, 0, 0,
                0.0, 0.0,
                true
        );
    }

    /**
     * 创建 Token 裁剪的结果。
     */
    public static ContextTrimResult forTokenTrim(
            List<AgentMessage> retainedMessages,
            int originalMessageCount,
            int retainedSystemCount,
            int retainedNonSystemCount,
            int maxMessages,
            int maxContextTokens,
            int availableMessageTokens,
            int additionalReservedTokens,
            int effectiveMessageBudget,
            Set<ContextTrimDiagnostic> diagnostics,
            long tokensBefore,
            long tokensAfter,
            boolean withinTokenBudget) {
        double utilizationBefore = maxContextTokens > 0
                ? (double) tokensBefore / maxContextTokens : 0.0;
        double utilizationAfter = maxContextTokens > 0
                ? (double) tokensAfter / maxContextTokens : 0.0;
        return new ContextTrimResult(
                retainedMessages,
                originalMessageCount,
                retainedMessages.size(),
                originalMessageCount - retainedMessages.size(),
                retainedSystemCount,
                retainedNonSystemCount,
                maxMessages,
                0, // Token 裁剪不使用 overshoot
                diagnostics,
                tokensBefore,
                tokensAfter,
                maxContextTokens,
                availableMessageTokens,
                additionalReservedTokens,
                effectiveMessageBudget,
                utilizationBefore,
                utilizationAfter,
                withinTokenBudget
        );
    }
}
