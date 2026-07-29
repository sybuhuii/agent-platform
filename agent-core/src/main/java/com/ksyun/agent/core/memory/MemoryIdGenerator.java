package com.ksyun.agent.core.memory;

/**
 * 长期记忆 ID 生成器。
 * <p>
 * 可使用 UUID v4。
 * 不得使用数据库自增假设。
 * 不得包含 userId、namespace、key 或 sessionId。
 * 不得在日志打印 ID 与完整 value 的组合。
 */
@FunctionalInterface
public interface MemoryIdGenerator {

    /**
     * 生成唯一的记忆 ID。
     *
     * @return 记忆 ID
     */
    String generate();
}
