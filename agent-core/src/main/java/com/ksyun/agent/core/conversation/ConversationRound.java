package com.ksyun.agent.core.conversation;

import java.util.Objects;
import java.util.Optional;

/** One invocation's user input and optional visible assistant result. */
public record ConversationRound(
        String runId,
        String userContent,
        String userDeduplicationKey,
        ConversationReply assistantReply
) {
    public ConversationRound {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(userContent, "userContent must not be null");
        Objects.requireNonNull(userDeduplicationKey, "userDeduplicationKey must not be null");
        runId = requireText(runId, "runId");
        userContent = requireText(userContent, "userContent");
        userDeduplicationKey = requireText(userDeduplicationKey, "userDeduplicationKey");
        if (assistantReply != null && !runId.equals(assistantReply.runId())) {
            throw new IllegalArgumentException("round and reply runId must match");
        }
    }

    public Optional<ConversationReply> reply() {
        return Optional.ofNullable(assistantReply);
    }

    private static String requireText(String value, String field) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
