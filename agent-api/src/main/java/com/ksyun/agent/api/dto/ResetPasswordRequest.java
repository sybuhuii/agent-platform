package com.ksyun.agent.api.dto;

/**
 * 重置密码请求 DTO。
 * <p>
 * 只包含新密码，不返回密码或 hash。
 */
public record ResetPasswordRequest(
        String newPassword
) {
}
