package com.ksyun.agent.api.dto;

/**
 * 会话消息响应 DTO。
 */
public record ConversationMessageResponse(
        String messageId,
        long sequenceNo,
        String role,
        String content,
        String runId,
        Boolean success,
        String errorCode,
        String runStatus,
        long createdAtEpochMillis
) {}
