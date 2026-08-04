package com.ksyun.agent.api.dto;

import java.util.List;

public record ConversationThreadPageResponse(
        List<ConversationThreadResponse> items,
        boolean hasMore,
        Boolean nextCursorPinned,
        Long nextCursorLastMessageAt,
        String nextCursorThreadId
) {
    public ConversationThreadPageResponse {
        items = List.copyOf(items);
    }
}
