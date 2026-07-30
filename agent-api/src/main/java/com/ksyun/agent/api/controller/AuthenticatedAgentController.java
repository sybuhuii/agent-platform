package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.AgentInvokeRequest;
import com.ksyun.agent.api.dto.AgentInvokeResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.agent.AuthenticatedAgentApplicationService;
import com.ksyun.agent.application.agent.AuthenticatedAgentRunResult;
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
 * 受认证保护的 Agent 调用 Controller。
 * <p>
 * 通过 @RequestAttribute 读取已认证的 UserSession，
 * 不得从 ThreadLocal 读取，不得把 HttpServletRequest 传入 ApplicationService。
 * <p>
 * Controller 只能：
 * 1. 读取认证拦截器注入的 UserSession
 * 2. 读取请求 DTO
 * 3. 调用 AuthenticatedAgentApplicationService
 * 4. 映射应用结果为响应 DTO
 * <p>
 * 禁止：
 * - Controller 生成 runId / threadId
 * - Controller 访问 CheckpointStore / ThreadConversationCheckpointService
 * - Controller 访问 ReactAgentEngine
 * - Controller 直接追加消息
 * - Controller 读取客户端 userId
 * - Controller 处理 ThreadExecutionLease
 * - Controller 根据 threadId 判断用户归属
 * - Controller 捕获所有异常后返回 200
 */
@RestController
@RequestMapping("/api/agent")
public class AuthenticatedAgentController {

    private final ObjectProvider<AuthenticatedAgentApplicationService> serviceProvider;

    public AuthenticatedAgentController(ObjectProvider<AuthenticatedAgentApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/invoke")
    public ResponseEntity<?> invoke(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestBody AgentInvokeRequest request
    ) {
        AuthenticatedAgentApplicationService service =
                serviceProvider.getIfAvailable();

        if (service == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_NOT_AVAILABLE,
                    "Agent service is not available");
        }

        Optional<String> threadId =
                request.threadId() != null
                        && !request.threadId().isBlank()
                        ? Optional.of(request.threadId())
                        : Optional.empty();

        AuthenticatedAgentRunResult runResult = service.invoke(
                session,
                request.agentName(),
                request.message(),
                threadId);

        return ResponseEntity.ok(toResponse(runResult));
    }
    private AgentInvokeResponse toResponse(AuthenticatedAgentRunResult runResult) {
        return new AgentInvokeResponse(
                runResult.runId(),
                runResult.threadId(),
                runResult.agentName(),
                runResult.result().success(),
                runResult.result().content(),
                runResult.result().errorCode(),
                runResult.result().evidence(),
                runResult.result().metadata(),
                runResult.result().status()
        );
    }
}
