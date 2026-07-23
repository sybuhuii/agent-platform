package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ModelInvokeRequest;
import com.ksyun.agent.api.dto.ModelInvokeResponse;
import com.ksyun.agent.api.dto.ToolCallResponse;
import com.ksyun.agent.api.dto.TokenUsageResponse;
import com.ksyun.agent.application.model.ModelDevApplicationService;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.model.TokenUsage;
import com.ksyun.agent.core.tool.ToolCall;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 开发验证 API Controller。
 * <p>
 * 仅当 agent.dev-api.enabled=true 时启用。
 * <p>
 * 禁止：
 * - Controller 不得直接注入 ChatModel、ChatClient、ModelClient 或 ToolRegistry
 * - Controller 不得执行 ToolCall
 * - Controller 不得允许客户端传入 RunContext、安全身份或自定义工具 Schema
 * - 不暴露完整 systemPrompt
 * - 不暴露 Spring AI 对象
 */
@RestController
@RequestMapping("/api/dev/model")
@ConditionalOnProperty(name = "agent.dev-api.enabled", havingValue = "true")
public class ModelDevController {

    private final ObjectProvider<ModelDevApplicationService> serviceProvider;

    public ModelDevController(ObjectProvider<ModelDevApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /**
     * 开发模型调用接口。
     * <p>
     * 模型未启用或未配置时返回明确的 HTTP 503。
     * 参数错误返回 HTTP 400。
     */
    @PostMapping("/invoke")
    public ResponseEntity<?> invoke(@RequestBody ModelInvokeRequest request) {
        ModelDevApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Model is not configured or unavailable"
                    ));
        }

        // 校验请求
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "errorCode", AgentErrorCode.INVALID_ARGUMENT.name(),
                            "message", "message must not be blank"
                    ));
        }

        // 调用服务（一次模型调用，不执行 ToolCall，不循环）
        ModelResponse response = service.invoke(
                request.message(),
                request.toolNames(),
                request.options()
        );

        return ResponseEntity.ok(toResponse(response));
    }

    private ModelInvokeResponse toResponse(ModelResponse response) {
        // 从 metadata 中提取 runId
        String runId = null;
        if (response.metadata() != null && response.metadata().containsKey("runId")) {
            runId = String.valueOf(response.metadata().get("runId"));
        }

        String content = response.message() != null ? response.message().content() : "";

        List<ToolCallResponse> toolCallResponses = response.message() != null
                ? response.message().toolCalls().stream()
                .map(this::toToolCallResponse)
                .toList()
                : List.of();

        TokenUsageResponse tokenUsageResponse = toTokenUsageResponse(response.tokenUsage());

        return new ModelInvokeResponse(
                runId,
                content,
                toolCallResponses,
                tokenUsageResponse,
                response.metadata()
        );
    }

    private ToolCallResponse toToolCallResponse(ToolCall toolCall) {
        return new ToolCallResponse(
                toolCall.id(),
                toolCall.name(),
                toolCall.arguments()
        );
    }

    private TokenUsageResponse toTokenUsageResponse(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return new TokenUsageResponse(0, 0, 0);
        }
        return new TokenUsageResponse(
                tokenUsage.inputTokens(),
                tokenUsage.outputTokens(),
                tokenUsage.totalTokens()
        );
    }
}
