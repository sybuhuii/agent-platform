package com.ksyun.agent.core.store;

/**
 * Checkpoint ID 生成器接口。
 * <p>
 * 位于 agent-core，实现位于 agent-infrastructure。
 * 不包含 userId、sessionId 和工具参数。
 */
@FunctionalInterface
public interface CheckpointIdGenerator {

    /**
     * 生成唯一 Checkpoint ID。
     *
     * @return Checkpoint ID
     */
    String generate();
}
