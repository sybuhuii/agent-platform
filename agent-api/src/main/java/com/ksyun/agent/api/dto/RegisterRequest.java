package com.ksyun.agent.api.dto;

/**
 * 公开注册请求 DTO。
 * 不允许客户端提交角色、权限、userId 或 sessionId。
 */
public record RegisterRequest(
        String username,
        String password,
        String confirmPassword
) {
}
