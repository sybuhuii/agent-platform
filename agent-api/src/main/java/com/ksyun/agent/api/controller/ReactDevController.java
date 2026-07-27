package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ReactInvokeRequest;
import com.ksyun.agent.api.dto.ReactInvokeResponse;
import com.ksyun.agent.application.react.ReactDevApplicationService;
import com.ksyun.agent.application.react.ReactDevRunResult;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
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
 * ReAct 开发验证 Controller。
 * <p>
 * 仅当 agent.dev-api.enabled=true 且 ReactDevApplicationService 存在时启用。
 * <p>
 * Controller 只依赖 ReactDevApplicationService，
 * 不注入 ChatModel、ModelClient、ModelInvocationGateway、ToolInvocationGateway、
 * AgentTool、ToolRegistry 或 CompiledGraph。
 */
@RestController
@RequestMapping("/api/dev/react")
@ConditionalOnProperty(name = "agent.dev-api.enabled", havingValue = "true")
public class ReactDevController {

    private final ObjectProvider<ReactDevApplicationService> serviceProvider;

    public ReactDevController(ObjectProvider<ReactDevApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/invoke")
    public ResponseEntity<?> invoke(@RequestBody ReactInvokeRequest request) {
        ReactDevApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "ReAct engine is not configured or unavailable"
                    ));
        }

        if (request.agentName() == null || request.agentName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "agentName must not be blank"
                    ));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "message must not be blank"
                    ));
        }

        ReactDevRunResult result = service.invoke(request.agentName(), request.message());

        // SUSPENDED 正常返回 200
        return ResponseEntity.ok(toResponse(result));
    }

    private ReactInvokeResponse toResponse(ReactDevRunResult runResult) {
        AgentResult agentResult = runResult.result();
        return new ReactInvokeResponse(
                runResult.runId(),
                runResult.threadId(),
                runResult.agentName(),
                agentResult.success(),
                agentResult.content(),
                agentResult.errorCode(),
                agentResult.evidence(),
                agentResult.metadata(),
                agentResult.status()
        );
    }
}
