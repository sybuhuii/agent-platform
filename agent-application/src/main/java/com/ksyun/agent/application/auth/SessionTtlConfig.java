package com.ksyun.agent.application.auth;

import java.time.Duration;

/**
 * Session TTL 配置。
 * <p>
 * ttlSeconds=0 表示永不过期。
 * 纯 Java record，不添加 Spring 注解。
 * 支持从 Duration 或秒数构造。
 *
 * @param ttlSeconds 会话存活时间（秒）
 */
public record SessionTtlConfig(long ttlSeconds) {

    public SessionTtlConfig {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must not be negative");
        }
    }

    /**
     * 从 Duration 构造。
     */
    public static SessionTtlConfig from(Duration duration) {
        if (duration == null) {
            return new SessionTtlConfig(0);
        }
        return new SessionTtlConfig(duration.getSeconds());
    }
}
