package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationMessageRole;
import com.ksyun.agent.core.conversation.ConversationParticipantType;
import com.ksyun.agent.core.conversation.ConversationReply;
import com.ksyun.agent.core.conversation.ConversationRound;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.conversation.MessageIdGenerator;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.store.ConversationStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Thread-safe in-memory conversation history store. */
public class InMemoryConversationStore implements ConversationStore {

    private static final int MAX_PAGE_SIZE = 101;
    private static final Comparator<ConversationThread> LIST_ORDER =
            Comparator.comparing(ConversationThread::pinned).reversed()
                    .thenComparing(ConversationThread::lastMessageAt, Comparator.reverseOrder())
                    .thenComparing(ConversationThread::threadId);

    private final ConcurrentHashMap<String, ThreadBucket> buckets = new ConcurrentHashMap<>();
    private final MessageIdGenerator messageIdGenerator;
    private final Clock clock;

    public InMemoryConversationStore(MessageIdGenerator messageIdGenerator, Clock clock) {
        this.messageIdGenerator = Objects.requireNonNull(messageIdGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public List<ConversationMessage> createThreadWithFirstRound(
            ConversationThread thread, ConversationRound round) {
        Objects.requireNonNull(thread, "thread must not be null");
        Objects.requireNonNull(round, "round must not be null");
        ThreadBucket candidate = new ThreadBucket(thread);
        ThreadBucket bucket = buckets.putIfAbsent(thread.threadId(), candidate);
        if (bucket == null) {
            bucket = candidate;
        }
        bucket.lock.lock();
        try {
            requireParticipant(requireOwned(bucket, thread.userId()),
                    thread.participantType(), thread.participantName());
            return bucket.appendRound(round);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public List<ConversationMessage> appendRound(
            String userId, String threadId,
            ConversationParticipantType expectedParticipantType,
            String expectedParticipantName,
            ConversationRound round) {
        Objects.requireNonNull(round, "round must not be null");
        ThreadBucket bucket = requireParticipant(
                requireOwned(buckets.get(normalize(threadId, "threadId")), normalize(userId, "userId")),
                expectedParticipantType, expectedParticipantName);
        bucket.lock.lock();
        try {
            return bucket.appendRound(round);
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public ConversationMessage appendAssistantMessage(
            String userId, String threadId,
            ConversationParticipantType expectedParticipantType,
            String expectedParticipantName,
            ConversationReply reply) {
        Objects.requireNonNull(reply, "reply must not be null");
        ThreadBucket bucket = requireParticipant(
                requireOwned(buckets.get(normalize(threadId, "threadId")), normalize(userId, "userId")),
                expectedParticipantType, expectedParticipantName);
        bucket.lock.lock();
        try {
            ConversationMessage message = bucket.appendAssistant(reply);
            bucket.touch(message.createdAt());
            return message;
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public Optional<ConversationThread> findThread(String userId, String threadId) {
        String safeUserId = normalize(userId, "userId");
        ThreadBucket bucket = buckets.get(normalize(threadId, "threadId"));
        return bucket == null || !bucket.thread.userId().equals(safeUserId)
                ? Optional.empty() : Optional.of(bucket.thread);
    }

    @Override
    public List<ConversationThread> listThreads(String userId, ThreadCursor before, int limit) {
        String safeUserId = normalize(userId, "userId");
        int safeLimit = Math.max(0, Math.min(limit, MAX_PAGE_SIZE));
        List<ConversationThread> result = buckets.values().stream()
                .map(bucket -> bucket.thread)
                .filter(thread -> thread.userId().equals(safeUserId) && !thread.archived())
                .sorted(LIST_ORDER)
                .filter(thread -> before == null || isAfterCursor(thread, before))
                .limit(safeLimit)
                .toList();
        return List.copyOf(result);
    }

    @Override
    public List<ConversationMessage> listMessages(
            String userId, String threadId, Long beforeSequence, int limit) {
        ThreadBucket bucket = requireOwned(
                buckets.get(normalize(threadId, "threadId")), normalize(userId, "userId"));
        int safeLimit = Math.max(0, Math.min(limit, MAX_PAGE_SIZE));
        bucket.lock.lock();
        try {
            List<ConversationMessage> filtered = bucket.messages.stream()
                    .filter(message -> beforeSequence == null || message.sequenceNo() < beforeSequence)
                    .toList();
            int start = Math.max(0, filtered.size() - safeLimit);
            return List.copyOf(filtered.subList(start, filtered.size()));
        } finally {
            bucket.lock.unlock();
        }
    }

    @Override
    public Optional<ConversationThread> rename(String userId, String threadId, String title) {
        return updateThread(userId, threadId, thread -> copy(thread, normalize(title, "title"),
                thread.pinned(), thread.archived()));
    }

    @Override
    public Optional<ConversationThread> setPinned(String userId, String threadId, boolean pinned) {
        return updateThread(userId, threadId, thread -> copy(thread, thread.title(), pinned, thread.archived()));
    }

    @Override
    public Optional<ConversationThread> archive(String userId, String threadId) {
        return updateThread(userId, threadId, thread -> copy(thread, thread.title(), false, true));
    }

    private Optional<ConversationThread> updateThread(
            String userId, String threadId,
            java.util.function.Function<ConversationThread, ConversationThread> updater) {
        ThreadBucket bucket = buckets.get(normalize(threadId, "threadId"));
        String safeUserId = normalize(userId, "userId");
        if (bucket == null || !bucket.thread.userId().equals(safeUserId) || bucket.thread.archived()) {
            return Optional.empty();
        }
        bucket.lock.lock();
        try {
            bucket.thread = updater.apply(bucket.thread);
            return Optional.of(bucket.thread);
        } finally {
            bucket.lock.unlock();
        }
    }

    private ConversationThread copy(ConversationThread thread, String title, boolean pinned, boolean archived) {
        return new ConversationThread(thread.threadId(), thread.userId(), title, pinned, archived,
                thread.participantType(), thread.participantName(), thread.createdAt(),
                thread.lastMessageAt(), clock.instant());
    }

    private boolean isAfterCursor(ConversationThread thread, ThreadCursor cursor) {
        if (thread.pinned() != cursor.pinned()) {
            return !thread.pinned() && cursor.pinned();
        }
        int time = thread.lastMessageAt().compareTo(cursor.lastMessageAt());
        return time < 0 || (time == 0 && thread.threadId().compareTo(cursor.threadId()) > 0);
    }

    private ThreadBucket requireOwned(ThreadBucket bucket, String userId) {
        if (bucket == null || !bucket.thread.userId().equals(userId)) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for user");
        }
        return bucket;
    }

    private ThreadBucket requireParticipant(
            ThreadBucket bucket, ConversationParticipantType type, String name) {
        String safeName = normalize(name, "participantName");
        if (type == null || bucket.thread.participantType() != type
                || !bucket.thread.participantName().equals(safeName)) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "Conversation belongs to a different participant");
        }
        return bucket;
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, field + " must not be blank");
        }
        return value.trim();
    }

    private final class ThreadBucket {
        private final ReentrantLock lock = new ReentrantLock();
        private final List<ConversationMessage> messages = new ArrayList<>();
        private final Map<String, ConversationMessage> dedupIndex = new HashMap<>();
        private ConversationThread thread;
        private long nextSequence;

        private ThreadBucket(ConversationThread thread) {
            this.thread = thread;
        }

        private List<ConversationMessage> appendRound(ConversationRound round) {
            List<ConversationMessage> result = new ArrayList<>(2);
            result.add(appendUser(round));
            round.reply().ifPresent(reply -> result.add(appendAssistant(reply)));
            touch(result.get(result.size() - 1).createdAt());
            return Collections.unmodifiableList(result);
        }

        private ConversationMessage appendUser(ConversationRound round) {
            return append(new ConversationMessage(messageIdGenerator.nextMessageId(), thread.threadId(),
                    nextSequence, ConversationMessageRole.USER, round.userContent(), round.runId(),
                    null, null, null, round.userDeduplicationKey(), clock.instant()));
        }

        private ConversationMessage appendAssistant(ConversationReply reply) {
            return append(new ConversationMessage(messageIdGenerator.nextMessageId(), thread.threadId(),
                    nextSequence, ConversationMessageRole.ASSISTANT, reply.content(), reply.runId(),
                    reply.success(), reply.errorCode(), reply.runStatus(), reply.deduplicationKey(), clock.instant()));
        }

        private ConversationMessage append(ConversationMessage candidate) {
            ConversationMessage existing = dedupIndex.get(candidate.deduplicationKey());
            if (existing != null) {
                if (samePayload(existing, candidate)) {
                    return existing;
                }
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Deduplication key conflict: payload differs");
            }
            messages.add(candidate);
            dedupIndex.put(candidate.deduplicationKey(), candidate);
            nextSequence++;
            return candidate;
        }

        private boolean samePayload(ConversationMessage left, ConversationMessage right) {
            return left.role() == right.role()
                    && left.content().equals(right.content())
                    && left.runId().equals(right.runId())
                    && Objects.equals(left.success(), right.success())
                    && Objects.equals(left.errorCode(), right.errorCode())
                    && left.runStatus() == right.runStatus();
        }

        private void touch(Instant at) {
            thread = new ConversationThread(thread.threadId(), thread.userId(), thread.title(),
                    thread.pinned(), thread.archived(), thread.participantType(), thread.participantName(),
                    thread.createdAt(), at, at);
        }
    }
}
