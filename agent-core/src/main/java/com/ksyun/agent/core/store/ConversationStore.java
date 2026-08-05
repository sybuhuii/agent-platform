package com.ksyun.agent.core.store;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationParticipantType;
import com.ksyun.agent.core.conversation.ConversationReply;
import com.ksyun.agent.core.conversation.ConversationRound;
import com.ksyun.agent.core.conversation.ConversationThread;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable, user-visible conversation history. */
public interface ConversationStore {

    List<ConversationMessage> createThreadWithFirstRound(
            ConversationThread thread, ConversationRound round);

    List<ConversationMessage> appendRound(
            String userId,
            String threadId,
            ConversationParticipantType expectedParticipantType,
            String expectedParticipantName,
            ConversationRound round);

    ConversationMessage appendAssistantMessage(
            String userId,
            String threadId,
            ConversationParticipantType expectedParticipantType,
            String expectedParticipantName,
            ConversationReply reply);

    Optional<ConversationThread> findThread(String userId, String threadId);

    List<ConversationThread> listThreads(String userId, ThreadCursor before, int limit);

    List<ConversationMessage> listMessages(String userId, String threadId, Long beforeSequence, int limit);

    Optional<ConversationThread> rename(String userId, String threadId, String title);

    Optional<ConversationThread> setPinned(String userId, String threadId, boolean pinned);

    Optional<ConversationThread> archive(String userId, String threadId);

    record ThreadCursor(boolean pinned, Instant lastMessageAt, String threadId) {
        public ThreadCursor {
            java.util.Objects.requireNonNull(lastMessageAt, "lastMessageAt must not be null");
            java.util.Objects.requireNonNull(threadId, "threadId must not be null");
            threadId = threadId.trim();
            if (threadId.isEmpty()) {
                throw new IllegalArgumentException("threadId must not be blank");
            }
        }
    }
}
