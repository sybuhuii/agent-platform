package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationMessageRole;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.conversation.MessageIdGenerator;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.store.ConversationStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存会话历史存储实现。
 * <p>
 * 行为与 {@link PostgresConversationStore} 的公开语义一致：
 * - Store 不生成 threadId / messageId；threadId 由调用方传入，messageId 由 MessageIdGenerator 生成。
 * - 创建重复 threadId 且内容一致幂等返回，内容不一致明确冲突。
 * - 同一 thread 内相同去重键+相同内容幂等，不同内容冲突。
 * - sequence_no 通过 thread 内原子计数器分配，连续递增。
 * - 所有查询和修改都包含 userId 归属条件。
 * <p>
 * 线程安全：每个 thread 一把锁，保证 sequence 和去重键的原子性。
 * 不添加 @Component，通过 @Bean 装配。
 */
public class InMemoryConversationStore implements ConversationStore {

    private static final Comparator<ConversationThread> LIST_ORDER =
            Comparator.comparing(ConversationThread::pinned).reversed()
                    .thenComparing(ConversationThread::lastMessageAt, Comparator.reverseOrder())
                    .thenComparing(ConversationThread::threadId);

    private final ConcurrentHashMap<String, ThreadBucket> buckets = new ConcurrentHashMap<>();
    private final MessageIdGenerator messageIdGenerator;

    public InMemoryConversationStore(MessageIdGenerator messageIdGenerator) {
        this.messageIdGenerator = Objects.requireNonNull(messageIdGenerator);
    }

    @Override
    public List<ConversationMessage> createThreadWithFirstRound(
            ConversationThread thread,
            String userMessage,
            String assistantMessage,
            String userDedupKey,
            String assistantDedupKey) {
        Objects.requireNonNull(thread, "thread must not be null");
        validateContent(userMessage);
        validateContent(assistantMessage);

        ThreadBucket bucket = new ThreadBucket(thread);
        ThreadBucket existing = buckets.putIfAbsent(thread.threadId(), bucket);

        if (existing != null) {
            // 已存在：校验归属，再按去重幂等/冲突处理
            ThreadBucket owned = requireOwned(existing, thread.userId());
            return owned.appendRound(userMessage, assistantMessage,
                    userDedupKey, assistantDedupKey, thread.lastMessageAt(), thread.updatedAt());
        }

        bucket.lock.lock();
        try {
            bucket.firstMessageAt = thread.createdAt();
            List<ConversationMessage> result = new ArrayList<>(2);
            result.add(bucket.appendMessage(ConversationMessageRole.USER, userMessage, userDedupKey));
            result.add(bucket.appendMessage(ConversationMessageRole.ASSISTANT, assistantMessage, assistantDedupKey));
            bucket.thread = withTimes(thread, thread.createdAt(), thread.lastMessageAt(), thread.lastMessageAt());
            return Collections.unmodifiableList(result);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public List<ConversationMessage> appendRound(
            String userId,
            String threadId,
            String userMessage,
            String assistantMessage,
            String userDedupKey,
            String assistantDedupKey) {
        validateUserId(userId);
        validateThreadId(threadId);
        validateContent(userMessage);
        validateContent(assistantMessage);

        ThreadBucket bucket = requireOwned(buckets.get(threadId.trim()), userId);
        bucket.lock.lock();
        try {
            Instant now = Instant.now();
            return bucket.appendRound(userMessage, assistantMessage, userDedupKey, assistantDedupKey, now, now);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public ConversationMessage appendAssistantMessage(
            String userId,
            String threadId,
            String assistantMessage,
            String dedupKey) {
        validateUserId(userId);
        validateThreadId(threadId);
        validateContent(assistantMessage);

        ThreadBucket bucket = requireOwned(buckets.get(threadId.trim()), userId);
        bucket.lock.lock();
        try {
            Instant now = Instant.now();
            List<ConversationMessage> round = bucket.appendRound(null, assistantMessage, null, dedupKey, now, now);
            return round.get(0);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public Optional<ConversationThread> findThread(String userId, String threadId) {
        validateUserId(userId);
        validateThreadId(threadId);
        ThreadBucket bucket = buckets.get(threadId.trim());
        if (bucket == null || !bucket.thread.userId().equals(userId.trim())) {
            return Optional.empty();
        }
        return Optional.of(bucket.thread);
    }

    @Override
    public List<ConversationThread> listThreads(String userId, ThreadCursor before, int limit) {
        validateUserId(userId);
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, MAX_PAGE_SIZE);

        List<ConversationThread> result = new ArrayList<>();
        for (ThreadBucket bucket : buckets.values()) {
            if (!bucket.thread.userId().equals(userId.trim())) continue;
            if (bucket.thread.archived()) continue;
            result.add(bucket.thread);
        }
        result.sort(LIST_ORDER);

        if (before != null) {
            result = applyCursor(result, before);
        }
        if (result.size() > safeLimit) {
            result = new ArrayList<>(result.subList(0, safeLimit));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<ConversationMessage> listMessages(
            String userId,
            String threadId,
            Long beforeSequence,
            int limit) {
        validateUserId(userId);
        validateThreadId(threadId);
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, MAX_PAGE_SIZE);

        ThreadBucket bucket = requireOwned(buckets.get(threadId.trim()), userId);
        bucket.lock.lock();
        try {
            List<ConversationMessage> all = bucket.messages;
            List<ConversationMessage> filtered = new ArrayList<>();
            for (ConversationMessage m : all) {
                if (beforeSequence != null && m.sequenceNo() >= beforeSequence) continue;
                filtered.add(m);
            }
            // 取最近 safeLimit 条，再升序返回
            int start = Math.max(0, filtered.size() - safeLimit);
            return Collections.unmodifiableList(new ArrayList<>(filtered.subList(start, filtered.size())));
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public Optional<ConversationThread> rename(String userId, String threadId, String title) {
        validateUserId(userId);
        validateThreadId(threadId);
        String safeTitle = title == null ? "" : title.trim();
        ThreadBucket bucket = requireOwned(buckets.get(threadId.trim()), userId);
        bucket.lock.lock();
        try {
            bucket.thread = withTitle(bucket.thread, safeTitle, Instant.now());
            return Optional.of(bucket.thread);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public Optional<ConversationThread> setPinned(String userId, String threadId, boolean pinned) {
        validateUserId(userId);
        validateThreadId(threadId);
        ThreadBucket bucket = requireOwned(buckets.get(threadId.trim()), userId);
        bucket.lock.lock();
        try {
            bucket.thread = withPinned(bucket.thread, pinned, Instant.now());
            return Optional.of(bucket.thread);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public Optional<ConversationThread> archive(String userId, String threadId) {
        validateUserId(userId);
        validateThreadId(threadId);
        ThreadBucket bucket = requireOwned(buckets.get(threadId.trim()), userId);
        bucket.lock.lock();
        try {
            bucket.thread = withArchived(bucket.thread, true, Instant.now());
            return Optional.of(bucket.thread);
        } finally {
            bucket.lock.unlock();
        }
    }

    // ---- 内部 ----

    private static final int MAX_PAGE_SIZE = 100;

    private List<ConversationThread> applyCursor(List<ConversationThread> sorted, ThreadCursor cursor) {
        List<ConversationThread> result = new ArrayList<>();
        boolean passed = false;
        for (ConversationThread t : sorted) {
            if (!passed) {
                if (t.threadId().equals(cursor.threadId())
                        && t.lastMessageAt().equals(cursor.lastMessageAt())) {
                    passed = true;
                }
                continue;
            }
            result.add(t);
        }
        return result;
    }

    private ThreadBucket requireOwned(ThreadBucket bucket, String userId) {
        if (bucket == null || !bucket.thread.userId().equals(userId)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for user");
        }
        return bucket;
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "userId must not be blank");
        }
    }

    private void validateThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "message content must not be blank");
        }
    }

    private ConversationThread withTimes(ConversationThread t, Instant created, Instant last, Instant updated) {
        return new ConversationThread(t.threadId(), t.userId(), t.title(), t.pinned(),
                t.archived(), t.agentName(), created, last, updated);
    }

    private ConversationThread withTitle(ConversationThread t, String title, Instant updated) {
        return new ConversationThread(t.threadId(), t.userId(), title, t.pinned(),
                t.archived(), t.agentName(), t.createdAt(), t.lastMessageAt(), updated);
    }

    private ConversationThread withPinned(ConversationThread t, boolean pinned, Instant updated) {
        return new ConversationThread(t.threadId(), t.userId(), t.title(), pinned,
                t.archived(), t.agentName(), t.createdAt(), t.lastMessageAt(), updated);
    }

    private ConversationThread withArchived(ConversationThread t, boolean archived, Instant updated) {
        return new ConversationThread(t.threadId(), t.userId(), t.title(), t.pinned(),
                archived, t.agentName(), t.createdAt(), t.lastMessageAt(), updated);
    }

    /**
     * 单个 thread 的存储桶，持有一把锁保证 sequence 和去重键原子性。
     */
    private final class ThreadBucket {
        private final ReentrantLock lock = new ReentrantLock();
        private ConversationThread thread;
        private final List<ConversationMessage> messages = new ArrayList<>();
        private long nextSequence = 0;
        private Instant firstMessageAt;
        private final java.util.Map<String, String> dedupIndex = new java.util.HashMap<>();

        ThreadBucket(ConversationThread thread) {
            this.thread = thread;
        }

        List<ConversationMessage> appendRound(
                String userContent,
                String assistantContent,
                String userDedupKey,
                String assistantDedupKey,
                Instant lastAt,
                Instant updatedAt) {
            List<ConversationMessage> result = new ArrayList<>(2);
            if (userContent != null) {
                result.add(appendMessage(ConversationMessageRole.USER, userContent, userDedupKey));
            }
            result.add(appendMessage(ConversationMessageRole.ASSISTANT, assistantContent, assistantDedupKey));
            Instant now = lastAt != null ? lastAt : Instant.now();
            thread = new ConversationThread(thread.threadId(), thread.userId(), thread.title(),
                    thread.pinned(), thread.archived(), thread.agentName(),
                    thread.createdAt(), now, updatedAt != null ? updatedAt : now);
            return Collections.unmodifiableList(result);
        }

        ConversationMessage appendMessage(ConversationMessageRole role, String content, String dedupKey) {
            String normalizedContent = content.trim();
            String normalizedDedup = dedupKey == null || dedupKey.isBlank() ? null : dedupKey.trim();

            if (normalizedDedup != null) {
                String existingContent = dedupIndex.get(normalizedDedup);
                if (existingContent != null) {
                    if (existingContent.equals(normalizedContent)) {
                        // 幂等：返回已有消息
                        for (ConversationMessage m : messages) {
                            if (normalizedDedup.equals(m.deduplicationKey())) {
                                return m;
                            }
                        }
                    } else {
                        throw new AgentFrameworkException(
                                AgentErrorCode.INVALID_ARGUMENT,
                                "Deduplication key conflict: same key with different content");
                    }
                }
            }

            long seq = nextSequence++;
            Instant now = Instant.now();
            ConversationMessage msg = new ConversationMessage(
                    messageIdGenerator.nextMessageId(),
                    thread.threadId(), seq, role, normalizedContent, normalizedDedup, now);
            messages.add(msg);
            if (normalizedDedup != null) {
                dedupIndex.put(normalizedDedup, normalizedContent);
            }
            return msg;
        }
    }
}
