package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.memory.MemoryIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的长期记忆 ID 生成器。
 * <p>
 * 无状态、线程安全。不包含 userId、namespace、key 和 sessionId。
 * 不在日志打印 ID 与完整 value 的组合。
 */
public class UuidMemoryIdGenerator implements MemoryIdGenerator {

    private static final String PREFIX = "mem-";

    @Override
    public String generate() {
        return PREFIX + UUID.randomUUID();
    }
}
