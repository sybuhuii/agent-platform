package com.ksyun.agent.infrastructure.security;

import com.ksyun.agent.core.security.CredentialHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Objects;

/**
 * 基于 BCrypt 的凭证哈希实现。
 * <p>
 * 使用 Spring Security 的 BCryptPasswordEncoder，不引入整套 Spring Security Web。
 * 保持无状态和线程安全。不得添加 @Component，通过 @Bean 装配。
 * 不得在日志中记录密码或哈希。
 */
public class BCryptCredentialHasher implements CredentialHasher {

    private final BCryptPasswordEncoder encoder;

    public BCryptCredentialHasher() {
        this.encoder = new BCryptPasswordEncoder();
    }

    @Override
    public String hash(CharSequence rawCredential) {
        Objects.requireNonNull(rawCredential, "rawCredential must not be null");
        if (rawCredential.length() == 0) {
            throw new IllegalArgumentException("rawCredential must not be empty");
        }
        return encoder.encode(rawCredential);
    }

    @Override
    public boolean matches(CharSequence rawCredential, String encodedCredential) {
        Objects.requireNonNull(rawCredential, "rawCredential must not be null");
        Objects.requireNonNull(encodedCredential, "encodedCredential must not be null");
        return encoder.matches(rawCredential, encodedCredential);
    }
}
