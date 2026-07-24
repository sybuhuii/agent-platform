package com.ksyun.agent.core.security;

/**
 * Session ID 生成器接口。
 * <p>
 * 生成的 sessionId 必须不可预测。
 * 不得在 sessionId 中编码 userId。
 * 不得返回空字符串。
 */
public interface SessionIdGenerator {

    /**
     * 生成一个新的 Session ID。
     *
     * @return 不可预测的 Session ID
     */
    String generate();
}
