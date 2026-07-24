package com.ksyun.agent.core.security;

/**
 * 用户 ID 生成器接口。
 * <p>
 * 位于 agent-core，不依赖数据库自增假设。
 */
@FunctionalInterface
public interface UserIdGenerator {

    /**
     * 生成新的用户 ID。
     *
     * @return 新用户 ID，非空
     */
    String nextUserId();
}
