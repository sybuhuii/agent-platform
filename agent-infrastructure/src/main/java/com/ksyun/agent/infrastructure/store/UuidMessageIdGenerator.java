package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.conversation.MessageIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的消息 ID 生成器。
 */
public class UuidMessageIdGenerator implements MessageIdGenerator {

    private static final String PREFIX = "msg-";

    @Override
    public String nextMessageId() {
        return PREFIX + UUID.randomUUID();
    }
}
