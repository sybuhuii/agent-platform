package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.run.ThreadIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的线程 ID 生成器。
 * <p>
 * 使用 UUID v4，不包含 userId、sessionId 和 agentName。
 * 不得使用递增整数或时间戳。
 */
public class UuidThreadIdGenerator implements ThreadIdGenerator {

    private static final String PREFIX = "thr-";

    @Override
    public String generate() {
        return PREFIX + UUID.randomUUID();
    }
}
