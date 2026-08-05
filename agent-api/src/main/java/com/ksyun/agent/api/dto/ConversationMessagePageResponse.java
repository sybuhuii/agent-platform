package com.ksyun.agent.api.dto;

import java.util.List;

public record ConversationMessagePageResponse(
        List<ConversationMessageResponse> items,
        boolean hasMore,
        Long nextBeforeSequence
) {
    public ConversationMessagePageResponse {
        items = List.copyOf(items);
    }
}
