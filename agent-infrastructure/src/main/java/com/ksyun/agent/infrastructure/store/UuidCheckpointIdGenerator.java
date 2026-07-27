package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.store.CheckpointIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的 Checkpoint ID 生成器。
 * <p>
 * 无状态、线程安全。不包含 userId、sessionId 和工具参数。
 */
public class UuidCheckpointIdGenerator implements CheckpointIdGenerator {

    @Override
    public String generate() {
        return "chk-" + UUID.randomUUID();
    }
}
