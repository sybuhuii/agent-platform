package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingRequest;
import com.ksyun.agent.core.context.ContextProcessingResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.message.SummaryAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 上下文窗口管理器，纯 Java 实现。
 * <p>
 * 依赖 ContextProcessingPipeline、ContextProcessingRequestFactory 和 Clock。
 * <p>
 * 算法：
 * - 首次处理：候选消息等于完整历史，执行 Pipeline，生成 Snapshot
 * - 后续处理：读取完整历史中尚未被窗口消费的新消息，
 *   与 previousSnapshot.windowMessages 拼接后执行 Pipeline，
 *   更新 consumedHistoryMessageCount 和 processingSequence
 * <p>
 * 保证：
 * - 已经摘要的旧历史不会每轮重新加入候选消息
 * - 同一批旧消息不会每轮重复摘要
 * - 新 ToolResult 可以追加到已有窗口
 * - 完整历史仍保存在 State
 * - 窗口只保存压缩后的模型上下文
 * - 不得修改 fullHistory
 * - 不得修改 previousSnapshot
 * - 不得使用 ThreadLocal
 * - 不得跨运行共享 Snapshot
 * - 同一 Bean 支持并发调用
 */
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    private final ContextProcessingPipeline pipeline;
    private final ContextProcessingRequestFactory requestFactory;
    private final Clock clock;
    private final boolean contextEnabled;
    private final TokenCounter tokenCounter;

    /**
     * 创建窗口管理器。
     *
     * @param pipeline       上下文处理流水线
     * @param requestFactory 请求工厂
     * @param clock          时钟
     * @param contextEnabled 上下文管理是否启用
     */
    public ContextWindowManager(ContextProcessingPipeline pipeline,
                                 ContextProcessingRequestFactory requestFactory,
                                 Clock clock,
                                 boolean contextEnabled) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.requestFactory = Objects.requireNonNull(requestFactory);
        this.clock = Objects.requireNonNull(clock);
        this.contextEnabled = contextEnabled;
        this.tokenCounter = null;
    }

    /**
     * 创建窗口管理器（含 TokenCounter）。
     *
     * @param pipeline       上下文处理流水线
     * @param requestFactory 请求工厂
     * @param clock          时钟
     * @param contextEnabled 上下文管理是否启用
     * @param tokenCounter   Token 计数器，用于临时上下文 Token 计算
     */
    public ContextWindowManager(ContextProcessingPipeline pipeline,
                                 ContextProcessingRequestFactory requestFactory,
                                 Clock clock,
                                 boolean contextEnabled,
                                 TokenCounter tokenCounter) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.requestFactory = Objects.requireNonNull(requestFactory);
        this.clock = Objects.requireNonNull(clock);
        this.contextEnabled = contextEnabled;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 更新上下文窗口。
     * <p>
     * 当 contextEnabled=false 时返回空，ReasonNode 应使用完整消息。
     *
     * @param fullHistory      完整运行历史
     * @param previousSnapshot 上一次的窗口快照（可能为空）
     * @return 窗口更新结果，上下文关闭时返回空
     */
    public Optional<ContextWindowUpdate> update(
            List<AgentMessage> fullHistory,
            Optional<ContextWindowSnapshot> previousSnapshot) {
        return update(fullHistory, previousSnapshot, List.of());
    }

    /**
     * 更新上下文窗口，支持临时上下文消息注入。
     * <p>
     * ephemeralContextMessages 本批只允许：
     * - 空列表
     * - 或一条 MemoryContextAgentMessage
     * <p>
     * 算法：
     * 1. 校验 ephemeral 列表
     * 2. 使用 TokenCounter 计算 ephemeralTokenCount
     * 3. 调用 ContextProcessingRequestFactory.create(candidateHistory, ephemeralTokenCount)
     * 4. 只对会话历史执行摘要、消息数和 Token 裁剪
     * 5. 生成 ContextWindowSnapshot 时只保存处理后的会话窗口
     * 6. 不得将 MemoryContextAgentMessage 写入 Snapshot
     * 7. 不得增加 consumedHistoryMessageCount
     * 8. 在处理后的会话窗口中插入临时记忆消息
     * 9. 插入位置为全部原始 System 消息之后、Summary 和普通消息之前
     * 10. 最终重新使用 TokenCounter 计数
     * 11. 最终总 Token 必须小于等于 ContextTokenBudget.availableMessageTokens
     * 12. 超过预算时抛 CONTEXT_BUDGET_EXCEEDED
     * 13. modelMessages 可以包含 MemoryContextAgentMessage
     * 14. snapshot.windowMessages 不得包含 MemoryContextAgentMessage
     * 15. 完整 fullHistory 不得被修改
     * 16. previousSnapshot 不得被修改
     * 17. 不得跨运行保存 ephemeral 消息
     *
     * @param fullHistory             完整运行历史
     * @param previousSnapshot        上一次的窗口快照（可能为空）
     * @param ephemeralContextMessages 临时上下文消息列表
     * @return 窗口更新结果，上下文关闭时返回空
     */
    public Optional<ContextWindowUpdate> update(
            List<AgentMessage> fullHistory,
            Optional<ContextWindowSnapshot> previousSnapshot,
            List<AgentMessage> ephemeralContextMessages) {
        Objects.requireNonNull(fullHistory, "fullHistory must not be null");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot must not be null");
        Objects.requireNonNull(ephemeralContextMessages, "ephemeralContextMessages must not be null");

        // 校验 ephemeral 列表
        validateEphemeralMessages(ephemeralContextMessages);

        // 上下文关闭时，不通过 ContextWindowManager 处理
        if (!contextEnabled || !requestFactory.isContextProcessingEnabled()) {
            return Optional.empty();
        }

        if (previousSnapshot.isEmpty()) {
            return Optional.of(processFirstWithEphemeral(fullHistory, ephemeralContextMessages));
        } else {
            return Optional.of(processIncrementalWithEphemeral(fullHistory, previousSnapshot.get(), ephemeralContextMessages));
        }
    }

    /**
     * 上下文管理是否启用。
     *
     * @return 是否启用
     */
    public boolean isContextEnabled() {
        return contextEnabled && requestFactory.isContextProcessingEnabled();
    }

    /**
     * 获取 TokenCounter（可能为 null）。
     */
    public TokenCounter getTokenCounter() {
        return tokenCounter;
    }

    private void validateEphemeralMessages(List<AgentMessage> ephemeralContextMessages) {
        if (ephemeralContextMessages.size() > 1) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "ephemeralContextMessages must contain at most one message"
            );
        }
        if (ephemeralContextMessages.size() == 1) {
            AgentMessage msg = ephemeralContextMessages.get(0);
            if (!(msg instanceof MemoryContextAgentMessage)) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "ephemeralContextMessages only supports MemoryContextAgentMessage"
                );
            }
        }
    }

    private ContextWindowUpdate processFirst(List<AgentMessage> fullHistory) {
        List<AgentMessage> candidateMessages = fullHistory;

        ContextProcessingRequest request = requestFactory.create(candidateMessages);
        ContextProcessingResult result = pipeline.process(request);

        Instant now = clock.instant();
        ContextProcessingTrace trace = ContextProcessingTrace.from(result, now);

        ContextWindowSnapshot snapshot = new ContextWindowSnapshot(
                result.processedMessages(),
                fullHistory.size(),
                1,
                trace,
                now
        );

        logProcessing(snapshot, trace);

        return new ContextWindowUpdate(snapshot, trace);
    }

    private ContextWindowUpdate processIncremental(List<AgentMessage> fullHistory,
                                                    ContextWindowSnapshot previous) {
        int consumed = previous.consumedHistoryMessageCount();

        if (consumed > fullHistory.size()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_WINDOW_STATE,
                    "consumedHistoryMessageCount (" + consumed
                            + ") exceeds full history size (" + fullHistory.size() + ")");
        }

        if (previous.windowMessages().isEmpty() && consumed > 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_WINDOW_STATE,
                    "windowMessages is empty but consumedHistoryMessageCount is " + consumed);
        }

        List<AgentMessage> newMessages = fullHistory.subList(consumed, fullHistory.size());

        List<AgentMessage> candidateMessages = new ArrayList<>(previous.windowMessages().size() + newMessages.size());
        candidateMessages.addAll(previous.windowMessages());
        candidateMessages.addAll(newMessages);

        ContextProcessingRequest request = requestFactory.create(candidateMessages);
        ContextProcessingResult result = pipeline.process(request);

        Instant now = clock.instant();
        ContextProcessingTrace trace = ContextProcessingTrace.from(result, now);

        ContextWindowSnapshot snapshot = new ContextWindowSnapshot(
                result.processedMessages(),
                fullHistory.size(),
                previous.processingSequence() + 1,
                trace,
                now
        );

        logProcessing(snapshot, trace);

        return new ContextWindowUpdate(snapshot, trace);
    }

    private ContextWindowUpdate processFirstWithEphemeral(List<AgentMessage> fullHistory,
                                                           List<AgentMessage> ephemeralContextMessages) {
        if (ephemeralContextMessages.isEmpty()) {
            return processFirst(fullHistory);
        }

        // 计算 ephemeral Token 数
        int ephemeralTokenCount = countEphemeralTokens(ephemeralContextMessages);

        // 使用 additionalReservedTokens 预留空间
        List<AgentMessage> candidateMessages = fullHistory;
        ContextProcessingRequest request = requestFactory.create(candidateMessages, ephemeralTokenCount);
        ContextProcessingResult result = pipeline.process(request);

        Instant now = clock.instant();
        ContextProcessingTrace trace = ContextProcessingTrace.from(result, now);

        // Snapshot 只保存处理后的会话窗口（不含 MemoryContextAgentMessage）
        ContextWindowSnapshot snapshot = new ContextWindowSnapshot(
                result.processedMessages(),
                fullHistory.size(),
                1,
                trace,
                now
        );

        // 在 modelMessages 中插入临时记忆消息
        List<AgentMessage> modelMessages = insertEphemeralMessages(result.processedMessages(), ephemeralContextMessages);

        // 重新计数验证
        validateTotalTokens(modelMessages, trace);

        logProcessing(snapshot, trace);

        return new ContextWindowUpdate(snapshot, modelMessages, trace);
    }

    private ContextWindowUpdate processIncrementalWithEphemeral(List<AgentMessage> fullHistory,
                                                                 ContextWindowSnapshot previous,
                                                                 List<AgentMessage> ephemeralContextMessages) {
        if (ephemeralContextMessages.isEmpty()) {
            return processIncremental(fullHistory, previous);
        }

        int consumed = previous.consumedHistoryMessageCount();

        if (consumed > fullHistory.size()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_WINDOW_STATE,
                    "consumedHistoryMessageCount (" + consumed
                            + ") exceeds full history size (" + fullHistory.size() + ")");
        }

        if (previous.windowMessages().isEmpty() && consumed > 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_WINDOW_STATE,
                    "windowMessages is empty but consumedHistoryMessageCount is " + consumed);
        }

        List<AgentMessage> newMessages = fullHistory.subList(consumed, fullHistory.size());

        List<AgentMessage> candidateMessages = new ArrayList<>(previous.windowMessages().size() + newMessages.size());
        candidateMessages.addAll(previous.windowMessages());
        candidateMessages.addAll(newMessages);

        // 计算 ephemeral Token 数
        int ephemeralTokenCount = countEphemeralTokens(ephemeralContextMessages);

        ContextProcessingRequest request = requestFactory.create(candidateMessages, ephemeralTokenCount);
        ContextProcessingResult result = pipeline.process(request);

        Instant now = clock.instant();
        ContextProcessingTrace trace = ContextProcessingTrace.from(result, now);

        // Snapshot 只保存处理后的会话窗口（不含 MemoryContextAgentMessage）
        ContextWindowSnapshot snapshot = new ContextWindowSnapshot(
                result.processedMessages(),
                fullHistory.size(),
                previous.processingSequence() + 1,
                trace,
                now
        );

        // 在 modelMessages 中插入临时记忆消息
        List<AgentMessage> modelMessages = insertEphemeralMessages(result.processedMessages(), ephemeralContextMessages);

        // 重新计数验证
        validateTotalTokens(modelMessages, trace);

        logProcessing(snapshot, trace);

        return new ContextWindowUpdate(snapshot, modelMessages, trace);
    }

    /**
     * 在处理后的会话窗口中插入临时记忆消息。
     * 插入位置：全部原始 System 消息之后、Summary 和普通消息之前。
     */
    private List<AgentMessage> insertEphemeralMessages(List<AgentMessage> processedMessages,
                                                        List<AgentMessage> ephemeralContextMessages) {
        if (ephemeralContextMessages.isEmpty()) {
            return processedMessages;
        }

        // 找到 System 消息的结束位置
        int systemEndIndex = 0;
        for (int i = 0; i < processedMessages.size(); i++) {
            if (processedMessages.get(i) instanceof SystemAgentMessage) {
                systemEndIndex = i + 1;
            } else {
                break;
            }
        }

        // 如果第一条非 System 消息是 Summary，则在 System 后、Summary 前插入
        // 否则在 System 后、其他消息前插入
        List<AgentMessage> result = new ArrayList<>(processedMessages.size() + ephemeralContextMessages.size());
        result.addAll(processedMessages.subList(0, systemEndIndex));
        result.addAll(ephemeralContextMessages);
        result.addAll(processedMessages.subList(systemEndIndex, processedMessages.size()));

        return List.copyOf(result);
    }

    private int countEphemeralTokens(List<AgentMessage> ephemeralContextMessages) {
        if (ephemeralContextMessages.isEmpty() || tokenCounter == null) {
            return 0;
        }
        return tokenCounter.count(ephemeralContextMessages);
    }

    private void validateTotalTokens(List<AgentMessage> modelMessages, ContextProcessingTrace trace) {
        if (tokenCounter == null) {
            return;
        }
        int totalTokens = tokenCounter.count(modelMessages);
        if (trace != null && trace.effectiveMessageBudget() > 0 && totalTokens > trace.effectiveMessageBudget()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "Total tokens including memory context (" + totalTokens
                            + ") exceed available message budget (" + trace.effectiveMessageBudget() + ")"
            );
        }
    }

    private void logProcessing(ContextWindowSnapshot snapshot, ContextProcessingTrace trace) {
        if (trace == null) {
            log.info("Context window updated: sequence={}, consumedHistory={}, windowMessages={}",
                    snapshot.processingSequence(),
                    snapshot.consumedHistoryMessageCount(),
                    snapshot.windowMessages().size());
            return;
        }
        log.info("Context window updated: sequence={}, consumedHistory={}, windowMessages={}, "
                        + "originalTokens={}, processedTokens={}, summaryApplied={}",
                snapshot.processingSequence(),
                snapshot.consumedHistoryMessageCount(),
                snapshot.windowMessages().size(),
                trace.originalTokenCount(),
                trace.processedTokenCount(),
                trace.summaryApplied());
    }
}
