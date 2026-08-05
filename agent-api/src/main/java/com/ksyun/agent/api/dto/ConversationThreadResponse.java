package com.ksyun.agent.api.dto;

/**
 * 会话线索响应 DTO。
 */
public record ConversationThreadResponse(
        String threadId,
        String title,
        boolean pinned,
        boolean archived,
        String participantType,
        String participantName,
        long createdAtEpochMillis,
        long lastMessageAtEpochMillis
) {}
