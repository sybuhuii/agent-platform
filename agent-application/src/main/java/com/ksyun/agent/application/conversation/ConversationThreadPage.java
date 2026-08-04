package com.ksyun.agent.application.conversation;

import com.ksyun.agent.core.conversation.ConversationThread;

import java.util.List;

public record ConversationThreadPage(
        List<ConversationThread> items,
        boolean hasMore,
        Boolean nextCursorPinned,
        Long nextCursorLastMessageAt,
        String nextCursorThreadId
) {
    public ConversationThreadPage {
        items = List.copyOf(items);
    }
}
