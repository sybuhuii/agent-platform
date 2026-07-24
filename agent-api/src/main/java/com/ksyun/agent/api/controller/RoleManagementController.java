package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.CreateRoleRequest;
import com.ksyun.agent.api.dto.RoleSummaryResponse;
import com.ksyun.agent.api.dto.UpdateRoleRequest;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.auth.RoleManagementApplicationService;
import com.ksyun.agent.application.auth.RoleSummary;
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
 * 角色管理 Controller。
 * <p>
 * 全部由 SessionAuthenticationInterceptor 保护。
 * 从 @RequestAttribute 读取已验证 UserSession。
 * Controller 只依赖 RoleManagementApplicationService，
 * 不得注入 RoleStore。不得在 Controller 中硬编码 ADMIN。
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleManagementController {

    private final ObjectProvider<RoleManagementApplicationService> serviceProvider;

    public RoleManagementController(ObjectProvider<RoleManagementApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @GetMapping
    public ResponseEntity<?> listRoles(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        RoleManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Role management service is not available"));
        }

        try {
            Collection<RoleSummary> roles = service.listRoles(session);
            return ResponseEntity.ok(roles.stream()
                    .map(this::toResponse)
                    .collect(Collectors.toUnmodifiableList()));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createRole(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestBody CreateRoleRequest request
    ) {
        RoleManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Role management service is not available"));
        }

        try {
            RoleSummary created = service.createRole(session, request.roleName(), request.description(), request.permissionCodes());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    @PutMapping("/{roleName}")
    public ResponseEntity<?> updateRolePermissions(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String roleName,
            @RequestBody UpdateRoleRequest request
    ) {
        RoleManagementApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Role management service is not available"));
        }

        try {
            RoleSummary updated = service.updateRolePermissions(session, roleName, request.description(), request.permissionCodes());
            return ResponseEntity.ok(toResponse(updated));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    private RoleSummaryResponse toResponse(RoleSummary summary) {
        return new RoleSummaryResponse(
                summary.roleName(),
                summary.description(),
                summary.permissionCodes()
        );
    }

    private HttpStatus mapErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case ROLE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ROLE_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
