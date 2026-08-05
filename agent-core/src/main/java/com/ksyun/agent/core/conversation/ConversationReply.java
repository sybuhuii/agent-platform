package com.ksyun.agent.core.conversation;

import com.ksyun.agent.core.run.RunStatus;

import java.util.Objects;

/** User-visible assistant result and its durable run metadata. */
public record ConversationReply(
        String runId,
        String content,
        boolean success,
        String errorCode,
        RunStatus runStatus,
        String deduplicationKey
) {
    public ConversationReply {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(runStatus, "runStatus must not be null");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey must not be null");
        runId = requireText(runId, "runId");
        content = requireText(content, "content");
        deduplicationKey = requireText(deduplicationKey, "deduplicationKey");
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
    }

    private static String requireText(String value, String field) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
