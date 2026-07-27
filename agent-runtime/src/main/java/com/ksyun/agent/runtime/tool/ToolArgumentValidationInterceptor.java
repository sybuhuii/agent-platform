package com.ksyun.agent.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolInvocation;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具参数校验拦截器。
 * <p>
 * 根据 ToolDefinition.inputSchema (JSON Schema) 校验 ToolCall.arguments。
 * 校验失败时直接返回 ToolResult.failure，不进入后续执行链。
 */
public class ToolArgumentValidationInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolArgumentValidationInterceptor.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolArgumentValidationInterceptor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = new ObjectMapper();
    }

    public ToolArgumentValidationInterceptor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public int order() {
        return -100;
    }

    @Override
    public ToolResult intercept(ToolInvocation invocation, ToolExecutionChain chain) {
        String toolName = invocation.toolCall().name();

        AgentTool tool = toolRegistry.find(toolName).orElse(null);
        if (tool == null) {
            // 工具不存在交给后续 TerminalToolExecutor 处理
            return chain.proceed(invocation);
        }

        String inputSchema = tool.definition().inputSchema();
        Map<String, Object> arguments = invocation.toolCall().arguments();

        // inputSchema 为空时按无参数工具处理
        if (inputSchema == null || inputSchema.isBlank()) {
            if (arguments.isEmpty()) {
                return chain.proceed(invocation);
            }
            return ToolResult.failure(
                    AgentErrorCode.INVALID_ARGUMENT.name(),
                    "Tool '" + toolName + "' expects no arguments but received: " + arguments.keySet()
            );
        }

        // 解析和校验 JSON Schema
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(inputSchema);

            JsonNode argumentsNode = objectMapper.valueToTree(arguments);

            Set<ValidationMessage> errors = schema.validate(argumentsNode);
            if (!errors.isEmpty()) {
                String errorSummary = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .limit(5)
                        .collect(Collectors.joining("; "));
                return ToolResult.failure(
                        AgentErrorCode.INVALID_ARGUMENT.name(),
                        "Argument validation failed for tool '" + toolName + "': " + errorSummary
                );
            }
        } catch (Exception e) {
            log.error("JSON Schema validation error for tool '{}': schema parse or validation failed",
                    toolName, e);
            return ToolResult.failure(
                    AgentErrorCode.INTERNAL_ERROR.name(),
                    "Internal error during argument validation for tool '" + toolName + "'"
            );
        }

        return chain.proceed(invocation);
    }
}
