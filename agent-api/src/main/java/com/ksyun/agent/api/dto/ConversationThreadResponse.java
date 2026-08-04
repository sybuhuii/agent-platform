package com.ksyun.agent.api.dto;

import java.time.Instant;

/**
 * 会话线索响应 DTO。
 */
public record ConversationThreadResponse(
        String threadId,
        String title,
        boolean pinned,
        boolean archived,
        String agentName,
        long createdAtEpochMillis,
        long lastMessageAtEpochMillis
) {}
