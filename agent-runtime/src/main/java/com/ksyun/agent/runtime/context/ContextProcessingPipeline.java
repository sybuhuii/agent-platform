package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingRequest;
import com.ksyun.agent.core.context.ContextProcessingResult;
import com.ksyun.agent.core.context.ContextSummaryOptions;
import com.ksyun.agent.core.context.ContextSummaryRequest;
import com.ksyun.agent.core.context.ContextSummaryResult;
import com.ksyun.agent.core.context.ContextSummarizer;
import com.ksyun.agent.core.context.ContextTokenBudget;
import com.ksyun.agent.core.context.ContextTrimDiagnostic;
import com.ksyun.agent.core.context.ContextTrimRequest;
import com.ksyun.agent.core.context.ContextTrimResult;
import com.ksyun.agent.core.context.ContextTrimmer;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 统一上下文处理流水线，纯 Java 实现。
 * <p>
 * 默认执行顺序（第七阶段第3批新增摘要流程）：
 * 消息历史验证 → 摘要触发判断 → 旧消息选择 → LLM摘要 → 用摘要替换旧消息
 * → 按消息数裁剪 → 按 Token 数裁剪 → 最终配对与 Token 验证 → 输出统一结果
 * <p>
 * 约束：
 * - 消息数裁剪关闭时跳过
 * - Token 裁剪关闭时跳过
 * - 两者都启用时必须先消息数后 Token 数
 * - 摘要必须发生在消息数和 Token 数裁剪之前
 * - 不得先删除旧消息后再尝试摘要
 * - 摘要失败不得导致旧消息被部分删除后丢失
 * - 调用摘要前保留原始不可变消息快照
 * - 每次process最多调用摘要模型一次
 * - 不跨请求保存摘要结果
 * - 不访问 ReactAgentState
 * - 不访问 CheckpointStore 或 MemoryStore
 * - 不使用 ThreadLocal
 * - 同一 Pipeline 支持并发调用
 * - 不得吞掉某一步诊断
 * - 诊断集合去重并保持稳定顺序
 * - 不得返回 null
 */
public class ContextProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(ContextProcessingPipeline.class);

    private final ContextTrimmer messageCountTrimmer;
    private final TokenCountContextTrimmer tokenCountTrimmer;
    private final TokenCounter tokenCounter;
    private final ContextMessageHistoryValidator historyValidator;
    private final ContextSummaryTrigger summaryTrigger;
    private final ContextSummarySelector summarySelector;
    private final ContextSummaryMerger summaryMerger;
    private final Optional<ContextSummarizer> summarizer;

    public ContextProcessingPipeline(ContextTrimmer messageCountTrimmer,
                                       TokenCountContextTrimmer tokenCountTrimmer,
                                       TokenCounter tokenCounter,
                                       ContextSummaryTrigger summaryTrigger,
                                       ContextSummarySelector summarySelector,
                                       ContextSummaryMerger summaryMerger,
                                       Optional<ContextSummarizer> summarizer) {
        this.messageCountTrimmer = Objects.requireNonNull(messageCountTrimmer);
        this.tokenCountTrimmer = Objects.requireNonNull(tokenCountTrimmer);
        this.tokenCounter = Objects.requireNonNull(tokenCounter);
        this.historyValidator = new ContextMessageHistoryValidator();
        this.summaryTrigger = Objects.requireNonNull(summaryTrigger);
        this.summarySelector = Objects.requireNonNull(summarySelector);
        this.summaryMerger = Objects.requireNonNull(summaryMerger);
        this.summarizer = summarizer == null ? Optional.empty() : summarizer;
    }

    /**
     * 处理上下文请求，按流水线执行裁剪。
     *
     * @param request 统一上下文处理请求
     * @return 统一处理结果，不为 null
     */
    public ContextProcessingResult process(ContextProcessingRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        List<AgentMessage> currentMessages = request.messages();
        // 保留原始不可变消息快照，用于摘要失败时回退
        List<AgentMessage> originalSnapshot = List.copyOf(currentMessages);

        // 初始 Token 计数
        long originalTokenCount = countTokens(currentMessages);

        // 步骤 0：消息历史验证（在裁剪前执行）
        historyValidator.validate(currentMessages);

        Set<ContextTrimDiagnostic> allDiagnostics = new LinkedHashSet<>();
        boolean messageCountTrimmed = false;
        boolean tokenTrimmed = false;
        boolean summaryTriggered = false;
        boolean summaryApplied = false;
        int summarizedMessageCount = 0;
        int summarySourceTokenCount = 0;
        int summaryTokenCount = 0;
        boolean existingSummaryReplaced = false;

        // ============ 摘要流程 ============

        ContextSummaryOptions summaryOptions = request.summaryOptions();

        if (!summaryOptions.summaryEnabled()) {
            // 摘要关闭
            allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_SKIPPED_DISABLED);
        } else if (!request.tokenTrimmingEnabled()) {
            // Token 裁剪未启用，无法计算预算和触发阈值
            allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_SKIPPED_DISABLED);
        } else {
            // 摘要启用，执行触发判断
            int effectiveBudget = request.effectiveMessageBudget();
            int currentTokenCount = (int) Math.min(originalTokenCount, Integer.MAX_VALUE);

            ContextSummaryTriggerDecision triggerDecision = summaryTrigger.evaluate(
                    currentTokenCount, effectiveBudget, summaryOptions.summaryTriggerRatio());

            if (!triggerDecision.triggered()) {
                // 未达到阈值
                allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_SKIPPED_BELOW_THRESHOLD);
            } else {
                // 达到阈值，记录触发
                summaryTriggered = true;
                allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_TRIGGERED);

                // 检查摘要器可用性
                if (summarizer.isEmpty()) {
                    // 摘要器不可用（无模型配置）
                    allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_UNAVAILABLE);
                    log.warn("Summary triggered but ContextSummarizer is unavailable, "
                            + "falling back to trimming only");
                } else {
                    // 选择需要摘要的旧消息
                    ContextSummarySelection selection = summarySelector.select(
                            currentMessages,
                            summaryOptions.summaryRecentGroupsToPreserve(),
                            summaryOptions.summaryMinSourceTokens());

                    if (!selection.hasSource()) {
                        // 没有可摘要旧消息
                        allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_SKIPPED_NO_SOURCE);
                    } else if (selection.sourceTokenCount() < summaryOptions.summaryMinSourceTokens()) {
                        // 源 Token 太少
                        allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_SKIPPED_SOURCE_TOO_SMALL);
                    } else {
                        // 存在源且摘要器可用，调用 LLM 摘要
                        try {
                            // 构建摘要请求
                            ContextSummaryRequest summaryRequest = new ContextSummaryRequest(
                                    selection.sourceMessages(),
                                    selection.existingSummary(),
                                    summaryOptions.summaryMaxTokens());

                            ContextSummaryResult summaryResult = summarizer.get().summarize(summaryRequest);

                            // 摘要成功，用摘要替换旧消息
                            currentMessages = summaryMerger.merge(selection, summaryResult.summaryMessage());
                            summaryApplied = true;
                            summarizedMessageCount = selection.sourceMessageCount();
                            summarySourceTokenCount = selection.sourceTokenCount();
                            summaryTokenCount = summaryResult.summaryTokenCount();
                            existingSummaryReplaced = summaryResult.existingSummaryReplaced();

                            allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_APPLIED);
                            if (existingSummaryReplaced) {
                                allDiagnostics.add(ContextTrimDiagnostic.EXISTING_SUMMARY_REPLACED);
                            }

                            log.info("Summary applied: sourceMessageCount={}, sourceTokenCount={}, "
                                    + "summaryTokenCount={}, existingSummaryReplaced={}",
                                    summarizedMessageCount, summarySourceTokenCount,
                                    summaryTokenCount, existingSummaryReplaced);

                        } catch (Exception e) {
                            // 摘要失败，降级为不摘要，继续执行普通裁剪
                            allDiagnostics.add(ContextTrimDiagnostic.SUMMARY_FAILED_FALLBACK_TO_TRIMMING);
                            log.warn("Summary failed, falling back to trimming only: {}", e.getMessage());

                            // 使用原始消息快照回退
                            currentMessages = originalSnapshot;
                            summaryTriggered = true;
                            summaryApplied = false;

                            // 不得生成假摘要
                            // 不得将异常文本作为摘要
                            // 不得中断普通上下文裁剪
                        }
                    }
                }
            }
        }

        // ============ 消息数裁剪 ============

        if (request.messageCountTrimmingEnabled()) {
            ContextTrimRequest mcRequest = ContextTrimRequest.forMessageCount(
                    currentMessages, request.maxMessages());
            ContextTrimResult mcResult = messageCountTrimmer.trim(mcRequest);

            currentMessages = mcResult.retainedMessages();
            messageCountTrimmed = mcResult.removedMessageCount() > 0;

            // 收集消息数裁剪诊断
            allDiagnostics.addAll(mcResult.diagnostics());
            if (messageCountTrimmed) {
                allDiagnostics.add(ContextTrimDiagnostic.MESSAGE_COUNT_TRIM_APPLIED);
            }
        }

        // ============ Token 数裁剪 ============

        if (request.tokenTrimmingEnabled()) {
            ContextTokenBudget tokenBudget = request.tokenBudget();
            ContextTrimRequest tokenRequest = ContextTrimRequest.withTokenBudget(
                    currentMessages,
                    request.maxMessages(),
                    tokenBudget,
                    request.additionalReservedTokens());

            ContextTrimResult tokenResult = tokenCountTrimmer.trim(tokenRequest);

            currentMessages = tokenResult.retainedMessages();
            tokenTrimmed = tokenResult.removedMessageCount() > 0;

            // 收集 Token 裁剪诊断
            allDiagnostics.addAll(tokenResult.diagnostics());
        }

        // ============ 最终 Token 校验 ============

        long processedTokenCount = countTokens(currentMessages);
        boolean withinBudget = true;

        if (request.tokenTrimmingEnabled()) {
            int effectiveBudget = request.effectiveMessageBudget();
            if (processedTokenCount > effectiveBudget) {
                throw new AgentFrameworkException(
                        AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                        "Final token count (" + processedTokenCount
                                + ") exceeds effective message budget (" + effectiveBudget + ")");
            }
            allDiagnostics.add(ContextTrimDiagnostic.FINAL_TOKEN_BUDGET_VERIFIED);
        }

        // ============ 最终工具配对验证 ============

        if (!currentMessages.isEmpty()) {
            historyValidator.validate(currentMessages);
        }

        // ============ 验证最多一条 SummaryAgentMessage ============

        int summaryCount = 0;
        for (AgentMessage msg : currentMessages) {
            if (msg instanceof SummaryAgentMessage) {
                summaryCount++;
            }
        }
        if (summaryCount > 1) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_MESSAGE_HISTORY,
                    "At most one SummaryAgentMessage allowed after processing, found: " + summaryCount);
        }

        // ============ 构建统一结果 ============

        int effectiveMessageBudget = request.tokenTrimmingEnabled()
                ? request.effectiveMessageBudget() : 0;

        return new ContextProcessingResult(
                currentMessages,
                request.messages().size(),
                currentMessages.size(),
                request.messages().size() - currentMessages.size(),
                originalTokenCount,
                processedTokenCount,
                effectiveMessageBudget,
                messageCountTrimmed,
                tokenTrimmed,
                summaryApplied,
                summaryTriggered,
                summarizedMessageCount,
                summarySourceTokenCount,
                summaryTokenCount,
                existingSummaryReplaced,
                withinBudget,
                allDiagnostics
        );
    }

    private long countTokens(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        try {
            return tokenCounter.count(messages);
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.TOKEN_COUNT_FAILED,
                    "Token counting failed: " + e.getMessage(), e);
        }
    }
}
