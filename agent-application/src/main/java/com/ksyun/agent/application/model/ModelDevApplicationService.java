package com.ksyun.agent.application.model;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开发验证用的单次模型调用服务。
 * <p>
 * 只负责开发验证，不作为未来正式 Agent 业务入口。
 * <p>
 * 职责：
 * 1. 只负责开发验证用的一次模型调用
 * 2. 依赖 ModelInvocationGateway 和 ToolRegistry 抽象
 * 3. 接收 userMessage、toolNames、可选 options
 * 4. 校验 userMessage 非空
 * 5. 根据 toolNames 从 ToolRegistry 获取 ToolDefinition
 * 6. toolNames 为空时不向模型提供工具
 * 7. 不存在的工具名称返回 TOOL_NOT_FOUND
 * 8. 构造框架 ModelRequest
 * 9. 构造固定的开发 RunContext
 * 10. 调用 ModelInvocationGateway 一次
 * <p>
 * 禁止：
 * - 不得允许客户端提交 userId、roles、permissions、sessionId
 * - 不得执行返回的 ToolCall
 * - 不得再次调用模型
 * - 不得实现 ReAct 循环
 */
public class ModelDevApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ModelDevApplicationService.class);

    private static final String DEV_SYSTEM_PROMPT =
            "You are a tool-aware assistant. Return a tool call when a provided tool is required.";

    private static final String DEV_USER_ID = "dev-user";
    private static final String DEV_SESSION_ID = "dev-session";
    private static final String DEV_THREAD_ID = "dev-thread";

    private final ModelInvocationGateway modelInvocationGateway;
    private final ToolRegistry toolRegistry;
    private final RunIdGenerator runIdGenerator;

    public ModelDevApplicationService(ModelInvocationGateway modelInvocationGateway,
                                      ToolRegistry toolRegistry,
                                      RunIdGenerator runIdGenerator) {
        if (modelInvocationGateway == null) {
            throw new IllegalArgumentException("ModelInvocationGateway must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("ToolRegistry must not be null");
        }
        if (runIdGenerator == null) {
            throw new IllegalArgumentException("RunIdGenerator must not be null");
        }
        this.modelInvocationGateway = modelInvocationGateway;
        this.toolRegistry = toolRegistry;
        this.runIdGenerator = runIdGenerator;
    }

    /**
     * 执行一次开发验证模型调用。
     *
     * @param userMessage 用户消息，非空
     * @param toolNames   工具名称列表，可为空
     * @param options     模型选项，可为空
     * @return 模型响应
     */
    public ModelResponse invoke(String userMessage, List<String> toolNames, Map<String, Object> options) {
        // 校验 userMessage 非空
        if (userMessage == null || userMessage.isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT, "userMessage must not be blank"
            );
        }

        // 根据 toolNames 从 ToolRegistry 获取 ToolDefinition
        List<ToolDefinition> toolDefinitions = resolveToolDefinitions(toolNames);

        // 构造消息列表：固定 System 消息 + 用户消息
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new SystemAgentMessage(DEV_SYSTEM_PROMPT));
        messages.add(new UserAgentMessage(userMessage));

        // 构造 ModelRequest
        ModelRequest modelRequest = new ModelRequest(
                messages,
                toolDefinitions,
                options != null ? options : Map.of()
        );

        // 构造固定的开发 RunContext
        RunContext runContext = new RunContext(
                DEV_USER_ID,
                DEV_SESSION_ID,
                DEV_THREAD_ID,
                runIdGenerator.nextRunId(),
                Set.of(),
                Set.of()
        );

        // 调用 ModelInvocationGateway 一次，不循环
        return modelInvocationGateway.invoke(modelRequest, runContext);
    }

    /**
     * 根据 toolNames 从 ToolRegistry 获取 ToolDefinition。
     * toolNames 为空时不向模型提供工具。
     * 不存在的工具名称返回 TOOL_NOT_FOUND。
     */
    private List<ToolDefinition> resolveToolDefinitions(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }

        List<ToolDefinition> definitions = new ArrayList<>();
        for (String toolName : toolNames) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            var agentTool = toolRegistry.find(toolName);
            if (agentTool.isEmpty()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.TOOL_NOT_FOUND,
                        "Tool not found: " + toolName
                );
            }
            definitions.add(agentTool.get().definition());
        }
        return definitions;
    }
}
