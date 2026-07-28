package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ContextDemoRequest;
import com.ksyun.agent.api.dto.ContextDemoResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.context.ContextDemoApplicationService;
import com.ksyun.agent.application.context.ContextDemoCommand;
import com.ksyun.agent.application.context.ContextDemoResult;
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

/**
 * 上下文演示 Controller。
 * <p>
 * 接口：POST /api/context/demo
 * <p>
 * 约束：
 * - 必须由 SessionAuthenticationInterceptor 保护
 * - 从 request attribute 读取已验证 UserSession
 * - Controller 只依赖 ContextDemoApplicationService
 * - 不得直接注入 Pipeline 或 ModelInvocationGateway
 * - 不得直接生成消息历史
 * - 不得允许客户端提交消息列表
 * - 不得返回完整消息、摘要正文、sessionId
 * - 不得新增隐藏 dev 路径
 * - 使用专用请求和响应 DTO
 * - 参数错误返回 400
 * - 模型不可用按现有错误结构处理
 * - 纯处理模式 invokeModel=false 即使无模型配置也应可用
 */
@RestController
@RequestMapping("/api/context")
public class ContextDemoController {

    private final ObjectProvider<ContextDemoApplicationService> serviceProvider;

    public ContextDemoController(ObjectProvider<ContextDemoApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/demo")
    public ResponseEntity<?> demo(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestBody ContextDemoRequest request
    ) {
        ContextDemoApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Context demo service is not available"));
        }

        // 参数校验
        if (request.finalQuestion() == null || request.finalQuestion().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "finalQuestion must not be blank"));
        }

        ContextDemoCommand command;
        try {
            command = new ContextDemoCommand(
                    request.rounds(),
                    request.charactersPerMessage(),
                    request.includeToolInteractions(),
                    request.invokeModel(),
                    request.finalQuestion()
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", e.getMessage()));
        }

        try {
            ContextDemoResult result = service.execute(session, command);
            ContextDemoResponse response = toResponse(result);
            return ResponseEntity.ok(response);
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    private ContextDemoResponse toResponse(ContextDemoResult result) {
        return new ContextDemoResponse(
                result.runId(),
                result.originalMessageCount(),
                result.processedMessageCount(),
                result.originalTokenCount(),
                result.processedTokenCount(),
                result.effectiveMessageBudget(),
                result.messageCountTrimmed(),
                result.tokenTrimmed(),
                result.summaryTriggered(),
                result.summaryApplied(),
                result.summarizedMessageCount(),
                result.diagnostics(),
                result.modelInvoked(),
                result.modelContent(),
                result.modelErrorCode()
        );
    }

    private HttpStatus mapErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case MODEL_NOT_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MODEL_INVOCATION_FAILED -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
