package com.ksyun.agent.infrastructure.security;

import com.ksyun.agent.core.security.UserIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的用户 ID 生成器。
 * <p>
 * 不依赖数据库自增假设。不添加 @Component，通过 @Bean 装配。
 */
public class UuidUserIdGenerator implements UserIdGenerator {

    private static final String PREFIX = "usr-";

    @Override
    public String nextUserId() {
        return PREFIX + UUID.randomUUID();
    }
}
