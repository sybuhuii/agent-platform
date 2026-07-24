package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.CreateUserRequest;
import com.ksyun.agent.api.dto.ResetPasswordRequest;
import com.ksyun.agent.api.dto.UpdateUserRequest;
import com.ksyun.agent.api.dto.UserSummaryResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.auth.UserManagementApplicationService;
import com.ksyun.agent.application.auth.UserSummary;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理 Controller。
 * <p>
 * 全部由 SessionAuthenticationInterceptor 保护。
 * 从 @RequestAttribute 读取已验证 UserSession。
 * Controller 只依赖 UserManagementApplicationService，
 * 不得注入 UserStore、RoleStore 或 SessionStore。
 * 不得在 Controller 中判断角色或权限。
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {

    private final ObjectProvider<UserManagementApplicationService> serviceProvider;

    public UserManagementController(ObjectProvider<UserManagementApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @GetMapping
    public ResponseEntity<?> listUsers(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        UserManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "User management service is not available"));
        }

        try {
            Collection<UserSummary> users = service.listUsers(session);
            return ResponseEntity.ok(users.stream()
                    .map(this::toResponse)
                    .collect(Collectors.toUnmodifiableList()));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createUser(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestBody CreateUserRequest request
    ) {
        UserManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "User management service is not available"));
        }

        try {
            UserSummary created = service.createUser(session, request.username(), request.password(), request.roleNames());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    @PutMapping("/{userId}")
    public ResponseEntity<?> updateUser(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String userId,
            @RequestBody UpdateUserRequest request
    ) {
        UserManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "User management service is not available"));
        }

        try {
            UserSummary updated = service.updateUser(session, userId, request.roleNames(), request.enabled());
            return ResponseEntity.ok(toResponse(updated));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    @PostMapping("/{userId}/reset-password")
    public ResponseEntity<?> resetPassword(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String userId,
            @RequestBody ResetPasswordRequest request
    ) {
        UserManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "User management service is not available"));
        }

        try {
            service.resetPassword(session, userId, request.newPassword());
            return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    private UserSummaryResponse toResponse(UserSummary summary) {
        return new UserSummaryResponse(
                summary.userId(),
                summary.username(),
                summary.roleNames(),
                summary.enabled()
        );
    }

    private HttpStatus mapErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case USER_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case ROLE_NOT_FOUND -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
