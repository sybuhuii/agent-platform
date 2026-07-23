package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.SupervisorDevInvokeRequest;
import com.ksyun.agent.api.dto.SupervisorDevInvokeResponse;
import com.ksyun.agent.application.supervisor.SupervisorDevApplicationService;
import com.ksyun.agent.application.supervisor.SupervisorDevRunResult;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Supervisor 开发验证 Controller。
 * <p>
 * 仅当 agent.dev-api.enabled=true 且 SupervisorDevApplicationService 存在时启用。
 * <p>
 * Controller 只依赖 SupervisorDevApplicationService，
 * 不注入 ChatModel、ChatClient、ModelClient、ModelInvocationGateway、
 * ReactAgentEngine、ToolInvocationGateway、AgentRegistry、SupervisorRegistry、
 * AgentTool、CompiledGraph。
 */
@RestController
@RequestMapping("/api/dev/supervisor")
@ConditionalOnProperty(name = "agent.dev-api.enabled", havingValue = "true")
public class SupervisorDevController {

    private final ObjectProvider<SupervisorDevApplicationService> serviceProvider;

    public SupervisorDevController(ObjectProvider<SupervisorDevApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/invoke")
    public ResponseEntity<?> invoke(@RequestBody SupervisorDevInvokeRequest request) {
        SupervisorDevApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Supervisor engine is not configured or unavailable"
                    ));
        }

        if (request.supervisorName() == null || request.supervisorName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "supervisorName must not be blank"
                    ));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "message must not be blank"
                    ));
        }

        SupervisorDevRunResult result = service.invoke(request.supervisorName(), request.message());

        return ResponseEntity.ok(toResponse(result));
    }

    private SupervisorDevInvokeResponse toResponse(SupervisorDevRunResult runResult) {
        AgentResult agentResult = runResult.result();
        return new SupervisorDevInvokeResponse(
                runResult.runId(),
                runResult.threadId(),
                runResult.supervisorName(),
                agentResult.success(),
                agentResult.content(),
                agentResult.errorCode(),
                agentResult.evidence(),
                agentResult.metadata()
        );
    }
}
