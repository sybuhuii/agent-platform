package com.ksyun.agent.api.dto;

/**
 * 列出会话请求参数。
 */
public record ListThreadsRequest(
        String cursorThreadId,
        Long cursorLastMessageAtEpochMillis,
        Integer pageSize
) {}
