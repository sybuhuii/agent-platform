package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.SupervisorInvokeRequest;
import com.ksyun.agent.api.dto.SupervisorInvokeResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.supervisor.AuthenticatedSupervisorApplicationService;
import com.ksyun.agent.application.supervisor.AuthenticatedSupervisorRunResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 受认证保护的 Supervisor 调用 Controller。
 * <p>
 * 通过 @RequestAttribute 读取已认证的 UserSession，
 * 不得从 ThreadLocal 读取，不得把 HttpServletRequest 传入 ApplicationService。
 */
@RestController
@RequestMapping("/api/supervisor")
public class AuthenticatedSupervisorController {

    private final ObjectProvider<AuthenticatedSupervisorApplicationService> serviceProvider;

    public AuthenticatedSupervisorController(ObjectProvider<AuthenticatedSupervisorApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/invoke")
    public ResponseEntity<?> invoke(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestBody SupervisorInvokeRequest request
    ) {
        AuthenticatedSupervisorApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Supervisor service is not available"));
        }

        if (request.supervisorName() == null || request.supervisorName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "supervisorName must not be blank"));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "message must not be blank"));
        }

        try {
            Optional<String> threadId = (request.threadId() != null && !request.threadId().isBlank())
                    ? Optional.of(request.threadId()) : Optional.empty();
            AuthenticatedSupervisorRunResult runResult = service.invoke(
                    session, request.supervisorName(), request.message(), threadId);
            return ResponseEntity.ok(toResponse(runResult));
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    private SupervisorInvokeResponse toResponse(AuthenticatedSupervisorRunResult runResult) {
        return new SupervisorInvokeResponse(
                runResult.runId(),
                runResult.threadId(),
                runResult.supervisorName(),
                runResult.result().success(),
                runResult.result().content(),
                runResult.result().errorCode(),
                runResult.result().evidence(),
                runResult.result().metadata()
        );
    }

    private HttpStatus mapErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case SUPERVISOR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_ARGUMENT, INVALID_THREAD_ID -> HttpStatus.BAD_REQUEST;
            case MODEL_NOT_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MODEL_INVOCATION_FAILED -> HttpStatus.BAD_GATEWAY;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case THREAD_NOT_FOUND, THREAD_PARTICIPANT_MISMATCH -> HttpStatus.NOT_FOUND;
            case THREAD_BUSY, THREAD_SUSPENDED -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
