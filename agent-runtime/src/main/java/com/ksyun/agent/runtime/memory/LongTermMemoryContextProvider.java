package com.ksyun.agent.runtime.memory;

import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.memory.MemoryCategory;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.memory.MemoryContextOptions;
import com.ksyun.agent.core.memory.MemoryEntry;
import com.ksyun.agent.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 长期记忆上下文 Provider，纯 Java 实现。
 * <p>
 * 按 RunContext.userId 读取长期记忆，选择、排序和限制注入模型的记忆条目。
 * <p>
 * 依赖：MemoryStore、MemoryContextRenderer、TokenCounter、MemoryContextOptions、Clock。
 * <p>
 * 不得接收 Session ID 或 threadId。
 * 不得调用 CheckpointStore 或模型。
 * 不得修改 MemoryEntry。
 * 不得跨请求缓存某个用户的记忆。
 * 不得使用 ThreadLocal。
 * 同一个 Provider 支持并发调用。
 * MemoryStore 异常转换为 MEMORY_STORE_FAILED。
 * enabled=false 时返回空上下文。
 * 没有记忆时返回空上下文。
 * 不得伪造默认偏好。
 */
public class LongTermMemoryContextProvider {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryContextProvider.class);

    /**
     * 记忆类别排序优先级：RULE > PREFERENCE > PROFILE > FACT
     */
    private static final Comparator<MemoryEntry> CATEGORY_PRIORITY = Comparator.comparingInt(
            (MemoryEntry e) -> categoryPriority(e.category())
    );

    private final MemoryStore memoryStore;
    private final MemoryContextRenderer renderer;
    private final TokenCounter tokenCounter;
    private final MemoryContextOptions options;
    private final Clock clock;

    private Comparator<MemoryEntry> memoryComparator() {
        java.util.Map<String, Integer> namespaceOrder =
                new java.util.HashMap<>();

        for (int index = 0;
             index < options.namespaces().size();
             index++) {
            namespaceOrder.putIfAbsent(
                    options.namespaces().get(index),
                    index);
        }

        return Comparator
                .comparingInt((MemoryEntry entry) ->
                        categoryPriority(entry.category()))
                .thenComparing(
                        MemoryEntry::updatedAt,
                        Comparator.reverseOrder())
                .thenComparingInt(entry ->
                        namespaceOrder.getOrDefault(
                                entry.namespace(),
                                Integer.MAX_VALUE))
                .thenComparing(MemoryEntry::key)
                .thenComparing(MemoryEntry::memoryId);
    }

    public LongTermMemoryContextProvider(MemoryStore memoryStore,
                                          MemoryContextRenderer renderer,
                                          TokenCounter tokenCounter,
                                          MemoryContextOptions options,
                                          Clock clock) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 加载当前用户的长期记忆上下文。
     *
     * @param userId 已认证用户 ID，不得为空
     * @return 长期记忆上下文
     */
    public LongTermMemoryContext load(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }

        // 配置关闭时返回空上下文
        if (!options.enabled()) {
            return LongTermMemoryContext.empty();
        }

        // 按配置 namespaces 逐个读取 MemoryStore
        List<MemoryEntry> allEntries;
        try {
            allEntries = loadEntries(userId);
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            log.error("MemoryStore failed for userId: {}", userId, e);
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to read memory store for user",
                    e
            );
        }

        // 没有记忆时返回空上下文
        if (allEntries.isEmpty()) {
            return LongTermMemoryContext.empty();
        }

        int totalEntryCount = allEntries.size();

        // 按固定规则排序
        List<MemoryEntry> sorted = new ArrayList<>(allEntries);
        sorted.sort(memoryComparator());

        // 选择算法：逐条尝试加入，不超过 maxEntries 和 maxInjectedTokens
        List<MemoryEntry> selected = new ArrayList<>();
        int estimatedTokens = 0;
        boolean truncated = false;

        for (MemoryEntry entry : sorted) {
            if (selected.size() >= options.maxEntries()) {
                truncated = true;
                break;
            }

            List<MemoryEntry> candidate =
                    new ArrayList<>(selected.size() + 1);
            candidate.addAll(selected);
            candidate.add(entry);

            String candidateContent = renderer.render(candidate);
            if (candidateContent == null
                    || candidateContent.isBlank()) {
                continue;
            }

            MemoryContextAgentMessage candidateMessage =
                    new MemoryContextAgentMessage(
                            candidateContent,
                            candidate.size(),
                            clock.instant());

            int candidateTokens =
                    tokenCounter.count(candidateMessage);

            if (candidateTokens > options.maxInjectedTokens()) {
                truncated = true;
                continue;
            }

            selected.add(entry);
            estimatedTokens = candidateTokens;
        }

        if (selected.isEmpty()) {
            return new LongTermMemoryContext(
                    Optional.empty(), totalEntryCount, 0, 0, truncated, options.namespaces()
            );
        }

        // 渲染最终选中条目
        String content = renderer.render(selected);
        if (content == null || content.isBlank()) {
            return new LongTermMemoryContext(
                    Optional.empty(), totalEntryCount, 0, 0, truncated, options.namespaces()
            );
        }

        // 使用完整渲染后的消息重新计算 Token
        MemoryContextAgentMessage message = new MemoryContextAgentMessage(
                content, selected.size(), clock.instant()
        );
        int finalTokens = tokenCounter.count(message);

        if (finalTokens > options.maxInjectedTokens()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CONTEXT_BUDGET_EXCEEDED,
                    "Rendered long-term memory exceeds configured injection budget");
        }

        return new LongTermMemoryContext(
                Optional.of(message),
                totalEntryCount,
                selected.size(),
                finalTokens,
                truncated,
                options.namespaces()
        );
    }

    private List<MemoryEntry> loadEntries(String userId) {
        List<MemoryEntry> allEntries = new ArrayList<>();
        for (String namespace : options.namespaces()) {
            Collection<MemoryEntry> entries = memoryStore.list(userId, namespace);
            if (entries != null) {
                allEntries.addAll(entries);
            }
        }
        return allEntries;
    }

    private static int categoryPriority(MemoryCategory category) {
        return switch (category) {
            case RULE -> 0;
            case PREFERENCE -> 1;
            case PROFILE -> 2;
            case FACT -> 3;
        };
    }
}
