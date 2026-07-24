package com.ksyun.agent.core.security;

/**
 * 凭证哈希接口。
 * <p>
 * 不限定具体哈希算法。不得在 agent-core 依赖 Spring Security。
 * 不得提供明文比较实现。不得记录 rawCredential。
 * 参数为空明确拒绝。
 */
public interface CredentialHasher {

    /**
     * 对原始凭证进行哈希。
     *
     * @param rawCredential 原始凭证，不能为空
     * @return 哈希值
     */
    String hash(CharSequence rawCredential);

    /**
     * 校验原始凭证是否匹配哈希值。
     *
     * @param rawCredential      原始凭证，不能为空
     * @param encodedCredential 已编码的哈希值，不能为空
     * @return 是否匹配
     */
    boolean matches(CharSequence rawCredential, String encodedCredential);
}
