package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import com.ksyun.agent.runtime.registry.ToolRegistry;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认 Reason 节点实现。
 * <p>
 * 调用模型，决定是否需要工具调用。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultReactReasonNode implements ReactReasonNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactReasonNode.class);

    private final ModelInvocationGateway modelGateway;
    private final ToolRegistry toolRegistry;

    public DefaultReactReasonNode(ModelInvocationGateway modelGateway,
                                   ToolRegistry toolRegistry) {
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Map<String, Object> apply(ReactAgentState state) throws Exception {
        AgentDefinition definition = getAgentDefinition(state);
        RunContext runContext = getRunContext(state);
        int iteration = getIteration(state);

        // 若 iteration 已达上限，不再调用模型
        if (iteration >= definition.maxIterations()) {
            return Map.of(
                    STOP_REASON, ReactStopReason.MAX_ITERATIONS_REACHED,
                    PENDING_TOOL_CALLS, List.of(),
                    LATEST_TOOL_RESULTS, List.of()
            );
        }

        // 构造本轮允许的工具定义
        List<ToolDefinition> tools = resolveAllowedTools(definition);

        // 构造 ModelRequest
        ModelRequest request = new ModelRequest(getMessages(state), tools, Map.of());

        // 调用模型
        ModelResponse response;
        try {
            response = modelGateway.invoke(request, runContext);
        } catch (AgentFrameworkException e) {
            log.error("Reason node model invocation failed: runId={}, agent={}, iteration={}, errorCode={}",
                    runContext.runId(), definition.name(), iteration, e.getErrorCode());
            return Map.of(
                    STOP_REASON, ReactStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, e.getErrorCode(),
                    FAILURE_MESSAGE, "Model invocation failed",
                    PENDING_TOOL_CALLS, List.of(),
                    LATEST_TOOL_RESULTS, List.of()
            );
        } catch (Exception e) {
            log.error("Reason node model invocation failed: runId={}, agent={}, iteration={}",
                    runContext.runId(), definition.name(), iteration, e);
            return Map.of(
                    STOP_REASON, ReactStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                    FAILURE_MESSAGE, "Model invocation failed",
                    PENDING_TOOL_CALLS, List.of(),
                    LATEST_TOOL_RESULTS, List.of()
            );
        }

        AssistantAgentMessage assistantMsg = response.message();
        List<ToolCall> toolCalls = assistantMsg.toolCalls();

        // 追加 assistant 消息到 messages（追加语义）
        List<AgentMessage> newMessages = new ArrayList<>();
        newMessages.add(assistantMsg);

        int newIteration = iteration + 1;

        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 模型返回了 ToolCall，不设置 MODEL_COMPLETED，由 Router 决定走向
            return Map.of(
                    MESSAGES, newMessages,
                    PENDING_TOOL_CALLS, List.copyOf(toolCalls),
                    LATEST_TOOL_RESULTS, List.of(),
                    ITERATION, newIteration
            );
        }

        // 模型未返回 ToolCall
        String content = assistantMsg.content();
        if (content != null && !content.isBlank()) {
            return Map.of(
                    MESSAGES, newMessages,
                    PENDING_TOOL_CALLS, List.of(),
                    LATEST_TOOL_RESULTS, List.of(),
                    ITERATION, newIteration,
                    STOP_REASON, ReactStopReason.MODEL_COMPLETED
            );
        }

        // 模型既无有效文本也无 ToolCall
        log.error("Reason node received empty model response: runId={}, agent={}, iteration={}",
                runContext.runId(), definition.name(), iteration);
        return Map.of(
                MESSAGES, newMessages,
                PENDING_TOOL_CALLS, List.of(),
                LATEST_TOOL_RESULTS, List.of(),
                ITERATION, newIteration,
                STOP_REASON, ReactStopReason.MODEL_ERROR,
                FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                FAILURE_MESSAGE, "Model returned empty response"
        );
    }

    private List<ToolDefinition> resolveAllowedTools(AgentDefinition definition) {
        if (definition.allowedTools() == null || definition.allowedTools().isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (String toolName : definition.allowedTools()) {
            if (!toolRegistry.contains(toolName)) {
                // 工具不存在，记录并跳过，但标记失败
                log.error("Allowed tool not found in registry: toolName={}", toolName);
                throw new AgentFrameworkException(
                        AgentErrorCode.TOOL_NOT_FOUND,
                        "Allowed tool not found: " + toolName
                );
            }
            tools.add(toolRegistry.getRequired(toolName).definition());
        }
        return List.copyOf(tools);
    }
}
