package com.ksyun.agent.api.dto;

/**
 * 登录请求 DTO。
 * <p>
 * 不得包含 sessionId、userId、roles、permissions。
 */
public record LoginRequest(
        String username,
        String password
) {
}
