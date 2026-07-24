package com.ksyun.agent.infrastructure.security;

import com.ksyun.agent.core.security.SessionIdGenerator;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 基于 SecureRandom 的 Session ID 生成器。
 * <p>
 * 生成至少 128 bit 不可预测随机值，使用 URL 安全编码。
 * 不得使用用户名、时间戳、递增 ID 或 UUID 版本 1。
 * 不得在 sessionId 中编码 userId。
 * 不得记录生成的 sessionId。不得添加 @Component。
 */
public class SecureRandomSessionIdGenerator implements SessionIdGenerator {

    private static final int NUM_BYTES = 16; // 128 bit
    private final SecureRandom secureRandom;

    public SecureRandomSessionIdGenerator() {
        this.secureRandom = new SecureRandom();
    }

    @Override
    public String generate() {
        byte[] bytes = new byte[NUM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
