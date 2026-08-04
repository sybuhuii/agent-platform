package com.ksyun.agent.application.conversation;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationParticipantType;
import com.ksyun.agent.core.conversation.ConversationReply;
import com.ksyun.agent.core.conversation.ConversationRound;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.ConversationStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Application service for durable user-visible conversation history. */
public class ConversationHistoryApplicationService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TITLE_LENGTH = 80;

    private final ConversationStore conversationStore;
    private final Clock clock;

    public ConversationHistoryApplicationService(ConversationStore conversationStore, Clock clock) {
        this.conversationStore = Objects.requireNonNull(conversationStore);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<ConversationMessage> recordRound(
            UserSession session,
            String threadId,
            ConversationParticipantType participantType,
            String participantName,
            String userMessage,
            String runId,
            AgentResult result) {
        requireSession(session);
        String safeThreadId = requireText(threadId, "threadId");
        String safeParticipantName = requireText(participantName, "participantName");
        String safeRunId = requireText(runId, "runId");
        String safeUserMessage = requireText(userMessage, "message");
        Objects.requireNonNull(participantType, "participantType must not be null");
        Objects.requireNonNull(result, "result must not be null");

        ConversationReply reply = visibleReply(result, safeRunId,
                "invoke:" + safeRunId + ":assistant");
        ConversationRound round = new ConversationRound(
                safeRunId, safeUserMessage, "invoke:" + safeRunId + ":user", reply);
        Optional<ConversationThread> existing = conversationStore.findThread(session.userId(), safeThreadId);
        if (existing.isEmpty()) {
            Instant now = clock.instant();
            ConversationThread thread = new ConversationThread(
                    safeThreadId, session.userId(), deriveTitle(safeUserMessage), false, false,
                    participantType, safeParticipantName, now, now, now);
            return conversationStore.createThreadWithFirstRound(thread, round);
        }
        validateParticipant(existing.get(), participantType, safeParticipantName);
        return conversationStore.appendRound(session.userId(), safeThreadId,
                participantType, safeParticipantName, round);
    }

    public Optional<ConversationMessage> recordApprovalResume(
            UserSession session,
            String threadId,
            ConversationParticipantType participantType,
            String participantName,
            String runId,
            String approvalId,
            AgentResult result) {
        requireSession(session);
        String safeThreadId = requireText(threadId, "threadId");
        String safeParticipantName = requireText(participantName, "participantName");
        String safeRunId = requireText(runId, "runId");
        String safeApprovalId = requireText(approvalId, "approvalId");
        Objects.requireNonNull(participantType, "participantType must not be null");
        Objects.requireNonNull(result, "result must not be null");
        ConversationReply reply = visibleReply(result, safeRunId,
                "approval-resume:" + safeApprovalId + ":assistant");
        if (reply == null) {
            return Optional.empty();
        }
        Optional<ConversationThread> thread = conversationStore.findThread(session.userId(), safeThreadId);
        if (thread.isEmpty()) {
            return Optional.empty();
        }
        validateParticipant(thread.get(), participantType, safeParticipantName);
        return Optional.of(conversationStore.appendAssistantMessage(
                session.userId(), safeThreadId, participantType, safeParticipantName, reply));
    }

    public void validateContinuation(
            UserSession session,
            String threadId,
            ConversationParticipantType participantType,
            String participantName) {
        requireSession(session);
        ConversationThread thread = conversationStore.findThread(
                        session.userId(), requireText(threadId, "threadId"))
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.THREAD_NOT_FOUND, "Conversation thread not found for user"));
        validateParticipant(thread, participantType, requireText(participantName, "participantName"));
    }

    /** Validates durable participant ownership when visible history already exists. */
    public void validateContinuationIfPresent(
            UserSession session,
            String threadId,
            ConversationParticipantType participantType,
            String participantName) {
        requireSession(session);
        Optional<ConversationThread> thread = conversationStore.findThread(
                session.userId(), requireText(threadId, "threadId"));
        thread.ifPresent(existing -> validateParticipant(
                existing, participantType, requireText(participantName, "participantName")));
    }

    public ConversationThreadPage listThreads(
            UserSession session,
            Boolean cursorPinned,
            String cursorThreadId,
            Long cursorLastMessageAtEpochMillis,
            int pageSize) {
        requireSession(session);
        int limit = sanitizePageSize(pageSize);
        ConversationStore.ThreadCursor cursor = null;
        boolean anyCursorValue = cursorPinned != null || cursorThreadId != null
                || cursorLastMessageAtEpochMillis != null;
        if (anyCursorValue) {
            if (cursorPinned == null || cursorThreadId == null || cursorLastMessageAtEpochMillis == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "All conversation cursor fields are required together");
            }
            cursor = new ConversationStore.ThreadCursor(cursorPinned,
                    Instant.ofEpochMilli(cursorLastMessageAtEpochMillis), cursorThreadId);
        }
        List<ConversationThread> loaded = new ArrayList<>(
                conversationStore.listThreads(session.userId(), cursor, limit + 1));
        boolean hasMore = loaded.size() > limit;
        if (hasMore) {
            loaded.remove(loaded.size() - 1);
        }
        ConversationThread last = loaded.isEmpty() ? null : loaded.get(loaded.size() - 1);
        return new ConversationThreadPage(loaded, hasMore,
                last == null ? null : last.pinned(),
                last == null ? null : last.lastMessageAt().toEpochMilli(),
                last == null ? null : last.threadId());
    }

    public ConversationMessagePage listMessages(
            UserSession session, String threadId, Long beforeSequence, int pageSize) {
        requireSession(session);
        int limit = sanitizePageSize(pageSize);
        List<ConversationMessage> loaded = new ArrayList<>(conversationStore.listMessages(
                session.userId(), requireText(threadId, "threadId"), beforeSequence, limit + 1));
        boolean hasMore = loaded.size() > limit;
        if (hasMore) {
            loaded.remove(0);
        }
        Long nextBefore = loaded.isEmpty() ? null : loaded.get(0).sequenceNo();
        return new ConversationMessagePage(loaded, hasMore, nextBefore);
    }

    public Optional<ConversationThread> rename(UserSession session, String threadId, String title) {
        requireSession(session);
        String safeTitle = requireText(title, "title");
        if (safeTitle.length() > MAX_TITLE_LENGTH) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "title must not exceed " + MAX_TITLE_LENGTH + " characters");
        }
        return conversationStore.rename(session.userId(), requireText(threadId, "threadId"), safeTitle);
    }

    public Optional<ConversationThread> setPinned(UserSession session, String threadId, boolean pinned) {
        requireSession(session);
        return conversationStore.setPinned(session.userId(), requireText(threadId, "threadId"), pinned);
    }

    public Optional<ConversationThread> archive(UserSession session, String threadId) {
        requireSession(session);
        return conversationStore.archive(session.userId(), requireText(threadId, "threadId"));
    }

    private ConversationReply visibleReply(AgentResult result, String runId, String deduplicationKey) {
        if (result.content() == null || result.content().isBlank()) {
            return null;
        }
        return new ConversationReply(runId, result.content(), result.success(),
                result.errorCode(), Objects.requireNonNull(result.status(), "result status must not be null"),
                deduplicationKey);
    }

    private void validateParticipant(
            ConversationThread thread, ConversationParticipantType type, String name) {
        if (type == null || thread.participantType() != type || !thread.participantName().equals(name)) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "Conversation belongs to a different participant");
        }
    }

    private int sanitizePageSize(int pageSize) {
        return pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String deriveTitle(String message) {
        return message.length() <= MAX_TITLE_LENGTH ? message : message.substring(0, MAX_TITLE_LENGTH);
    }

    private void requireSession(UserSession session) {
        if (session == null) {
            throw new AgentFrameworkException(AgentErrorCode.SESSION_INVALID, "session must not be null");
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, field + " must not be blank");
        }
        return value.trim();
    }
}
