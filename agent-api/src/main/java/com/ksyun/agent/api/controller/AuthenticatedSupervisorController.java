package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.SupervisorInvokeRequest;
import com.ksyun.agent.api.dto.SupervisorInvokeResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.supervisor.AuthenticatedSupervisorApplicationService;
import com.ksyun.agent.application.supervisor.AuthenticatedSupervisorRunResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.security.UserSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ksyun.agent.core.exception.AgentFrameworkException;

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
        AuthenticatedSupervisorApplicationService service =
                serviceProvider.getIfAvailable();

        if (service == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_NOT_AVAILABLE,
                    "Supervisor service is not available");
        }

        Optional<String> threadId =
                request.threadId() != null
                        && !request.threadId().isBlank()
                        ? Optional.of(request.threadId())
                        : Optional.empty();

        AuthenticatedSupervisorRunResult runResult = service.invoke(
                session,
                request.supervisorName(),
                request.message(),
                threadId);

        return ResponseEntity.ok(toResponse(runResult));
    }
    private SupervisorInvokeResponse toResponse(AuthenticatedSupervisorRunResult runResult) {
        Map<String, Object> metadata = runResult.result().metadata();
        com.ksyun.agent.core.run.RunStatus resultStatus = runResult.result().status();
        boolean isSuspended = resultStatus == com.ksyun.agent.core.run.RunStatus.SUSPENDED;

        // SUSPENDED 时从 metadata 中提取子 Agent runId
        String approvalRunId = isSuspended && metadata != null
                ? safeGetString(metadata, "childRunId") : null;
        String parentRunId = isSuspended ? runResult.runId() : null;
        boolean isNested = isSuspended;

        return new SupervisorInvokeResponse(
                runResult.runId(),
                runResult.threadId(),
                runResult.supervisorName(),
                runResult.result().success(),
                runResult.result().content(),
                runResult.result().errorCode(),
                runResult.result().evidence(),
                metadata,
                approvalRunId,
                parentRunId,
                isNested
        );
    }

    private static String safeGetString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
