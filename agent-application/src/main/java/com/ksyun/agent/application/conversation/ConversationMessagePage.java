package com.ksyun.agent.application.conversation;

import com.ksyun.agent.core.conversation.ConversationMessage;

import java.util.List;

public record ConversationMessagePage(
        List<ConversationMessage> items,
        boolean hasMore,
        Long nextBeforeSequence
) {
    public ConversationMessagePage {
        items = List.copyOf(items);
    }
}
