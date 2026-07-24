package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.LoginRequest;
import com.ksyun.agent.api.dto.LoginResponse;
import com.ksyun.agent.api.dto.SessionInfoResponse;
import com.ksyun.agent.api.dto.UserInfoResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.auth.AuthApplicationService;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证 Controller。
 * <p>
 * /me 和 /logout 通过 @RequestAttribute 读取已认证的 UserSession，
 * 不得从 ThreadLocal 读取，不得把 HttpServletRequest 传入 ApplicationService。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ObjectProvider<AuthApplicationService> authServiceProvider;

    public AuthController(ObjectProvider<AuthApplicationService> authServiceProvider) {
        this.authServiceProvider = authServiceProvider;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        AuthApplicationService authService = authServiceProvider.getIfAvailable();
        if (authService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.AUTHENTICATION_FAILED.name(),
                            "message", "Authentication service is not available"));
        }

        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "Username and password must not be blank"));
        }

        try {
            UserSession session = authService.login(request.username(), request.password());
            return ResponseEntity.ok(toLoginResponse(session));
        } catch (AgentFrameworkException e) {
            // 登录失败统一返回 AUTHENTICATION_FAILED，不泄露用户名是否存在、用户是否被禁用
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("errorCode", AgentErrorCode.AUTHENTICATION_FAILED.name(),
                            "message", "Authentication failed"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestAttribute(name = AuthenticatedSessionAttributes.SESSION, required = false) UserSession session
    ) {
        AuthApplicationService authService = authServiceProvider.getIfAvailable();
        if (authService != null && session != null) {
            // 幂等：即使 session 已被删除也不报错
            try {
                authService.logout(session.sessionId());
            } catch (AgentFrameworkException e) {
                // session 已过期或不存在，视为已登出，不报错
            }
        }
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        return ResponseEntity.ok(toUserInfoResponse(session));
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSession(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        return ResponseEntity.ok(toSessionInfoResponse(session));
    }

    private LoginResponse toLoginResponse(UserSession session) {
        long expiresAtMillis = session.expiresAt() != null ? session.expiresAt().toEpochMilli() : 0;
        return new LoginResponse(
                session.sessionId(),
                session.username(),
                session.roles(),
                expiresAtMillis
        );
    }

    /**
     * /me 返回 UserInfoResponse：userId、username、roles、permissions，不含 sessionId。
     */
    private UserInfoResponse toUserInfoResponse(UserSession session) {
        return new UserInfoResponse(
                session.userId(),
                session.username(),
                session.roles(),
                session.permissions()
        );
    }

    /**
     * /session 旧路径兼容，返回 SessionInfoResponse。
     */
    private SessionInfoResponse toSessionInfoResponse(UserSession session) {
        long expiresAtMillis = session.expiresAt() != null ? session.expiresAt().toEpochMilli() : 0;
        return new SessionInfoResponse(
                session.sessionId(),
                session.username(),
                session.roles(),
                session.createdAt().toEpochMilli(),
                expiresAtMillis
        );
    }
}
