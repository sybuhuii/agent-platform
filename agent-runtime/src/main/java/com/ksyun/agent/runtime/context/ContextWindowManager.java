package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.context.ContextProcessingRequest;
import com.ksyun.agent.core.context.ContextProcessingResult;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
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
        Objects.requireNonNull(fullHistory, "fullHistory must not be null");
        Objects.requireNonNull(previousSnapshot, "previousSnapshot must not be null");

        if (!contextEnabled || !requestFactory.isContextProcessingEnabled()) {
            return Optional.empty();
        }

        if (previousSnapshot.isEmpty()) {
            return Optional.of(processFirst(fullHistory));
        } else {
            return Optional.of(processIncremental(fullHistory, previousSnapshot.get()));
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

    private ContextWindowUpdate processFirst(List<AgentMessage> fullHistory) {
        // 首次处理：候选消息等于完整历史
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

        return new ContextWindowUpdate(snapshot, snapshot.windowMessages(), trace);
    }

    private ContextWindowUpdate processIncremental(List<AgentMessage> fullHistory,
                                                    ContextWindowSnapshot previous) {
        int consumed = previous.consumedHistoryMessageCount();

        // 校验 consumedHistoryMessageCount <= fullHistory.size()
        if (consumed > fullHistory.size()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_WINDOW_STATE,
                    "consumedHistoryMessageCount (" + consumed
                            + ") exceeds full history size (" + fullHistory.size() + ")");
        }

        // 异常情况：windowMessages 为空但 consumed > 0
        if (previous.windowMessages().isEmpty() && consumed > 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_CONTEXT_WINDOW_STATE,
                    "windowMessages is empty but consumedHistoryMessageCount is " + consumed);
        }

        // 读取完整历史中尚未被窗口消费的新消息
        List<AgentMessage> newMessages = fullHistory.subList(consumed, fullHistory.size());

        // 候选消息：previousSnapshot.windowMessages + newMessages，保持原始顺序
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

        return new ContextWindowUpdate(snapshot, snapshot.windowMessages(), trace);
    }

    private void logProcessing(ContextWindowSnapshot snapshot, ContextProcessingTrace trace) {
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
