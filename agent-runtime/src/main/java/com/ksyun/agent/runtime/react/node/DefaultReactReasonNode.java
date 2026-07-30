package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.context.TokenCounter;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.runtime.context.ContextWindowManager;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.runtime.context.ContextWindowUpdate;
import com.ksyun.agent.runtime.memory.LongTermMemoryContext;
import com.ksyun.agent.runtime.memory.LongTermMemoryContextProvider;
import com.ksyun.agent.runtime.memory.MemoryContextTrace;
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
import java.util.Optional;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * 默认 Reason 节点实现。
 * <p>
 * 调用模型，决定是否需要工具调用。
 * 通过 ContextWindowManager 管理上下文窗口，模型只接收压缩后的消息。
 * 完整历史仍保存在 state.messages 中，不被裁剪结果覆盖。
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * Phase8 Batch5 扩展：
 * - 通过 LongTermMemoryContextProvider 读取当前用户长期记忆
 * - 将记忆作为临时 ephemeral 上下文注入 ContextWindowManager
 * - 更新 LATEST_MEMORY_CONTEXT_TRACE
 * - 不得把 MemoryContextAgentMessage 追加到 state.messages
 * - 不得把 MemoryContextAgentMessage 保存到 ThreadConversationState
 */
public class DefaultReactReasonNode implements ReactReasonNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactReasonNode.class);

    private final ModelInvocationGateway modelGateway;
    private final ToolRegistry toolRegistry;
    private final ContextWindowManager contextWindowManager;
    private final LongTermMemoryContextProvider memoryContextProvider;

    public DefaultReactReasonNode(ModelInvocationGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ContextWindowManager contextWindowManager) {
        this(modelGateway, toolRegistry, contextWindowManager, null);
    }

    public DefaultReactReasonNode(ModelInvocationGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ContextWindowManager contextWindowManager,
                                   LongTermMemoryContextProvider memoryContextProvider) {
        this.modelGateway = modelGateway;
        this.toolRegistry = toolRegistry;
        this.contextWindowManager = contextWindowManager;
        this.memoryContextProvider = memoryContextProvider;
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

        // 读取完整 state.messages
        List<AgentMessage> fullHistory = getMessages(state);

        // 读取可选 ContextWindowSnapshot
        ContextWindowSnapshot previousSnapshot = getContextWindowSnapshot(state);
        Optional<ContextWindowSnapshot> previousOpt = Optional.ofNullable(previousSnapshot);

        // Phase8 Batch5：读取当前用户长期记忆
        LongTermMemoryContext memoryContext = loadMemoryContext(runContext);
        List<AgentMessage> ephemeralContextMessages = resolveEphemeralContext(memoryContext);
        MemoryContextTrace memoryTrace = buildMemoryTrace(memoryContext);

        // 确定发送给模型的消息
        List<AgentMessage> modelMessages;
        ContextWindowSnapshot newSnapshot = null;
        ContextProcessingTrace newTrace = null;

        Optional<ContextWindowUpdate> windowUpdate = contextWindowManager.update(
                fullHistory, previousOpt, ephemeralContextMessages);
        if (windowUpdate.isPresent()) {
            ContextWindowUpdate update = windowUpdate.get();
            modelMessages = update.modelMessages();
            newSnapshot = update.snapshot();
            newTrace = update.trace();
        } else {
            if (!ephemeralContextMessages.isEmpty()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_CONTEXT_CONFIGURATION,
                        "Long-term memory injection requires enabled "
                                + "token-budget context management");
            }
            modelMessages = fullHistory;
        }

        // 构造本轮允许的工具定义
        List<ToolDefinition> tools = resolveAllowedTools(definition);

        // 使用 modelMessages 构造 ModelRequest
        ModelRequest request = new ModelRequest(modelMessages, tools, Map.of());

        // 调用模型
        ModelResponse response;
        try {
            response = modelGateway.invoke(request, runContext);
        } catch (AgentFrameworkException e) {
            log.error("Reason node model invocation failed: runId={}, agent={}, iteration={}, errorCode={}",
                    runContext.runId(), definition.name(), iteration, e.getErrorCode());
            Map<String, Object> errorResult = Map.of(
                    STOP_REASON, ReactStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, e.getErrorCode(),
                    FAILURE_MESSAGE, "Model invocation failed",
                    PENDING_TOOL_CALLS, List.of(),
                    LATEST_TOOL_RESULTS, List.of()
            );
            // 即使失败也更新窗口快照和追踪（如果存在）
            Map<String, Object> merged = mergeContextState(errorResult, newSnapshot, newTrace);
            merged = mergeMemoryTrace(merged, memoryTrace);
            return merged;
        } catch (Exception e) {
            log.error("Reason node model invocation failed: runId={}, agent={}, iteration={}",
                    runContext.runId(), definition.name(), iteration, e);
            Map<String, Object> errorResult = Map.of(
                    STOP_REASON, ReactStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                    FAILURE_MESSAGE, "Model invocation failed",
                    PENDING_TOOL_CALLS, List.of(),
                    LATEST_TOOL_RESULTS, List.of()
            );
            Map<String, Object> merged = mergeContextState(errorResult, newSnapshot, newTrace);
            merged = mergeMemoryTrace(merged, memoryTrace);
            return merged;
        }

        AssistantAgentMessage assistantMsg = response.message();
        List<ToolCall> toolCalls = assistantMsg.toolCalls();

        // 追加 assistant 消息到 messages（追加语义），注意是追加到完整历史
        List<AgentMessage> newMessages = new ArrayList<>();
        newMessages.add(assistantMsg);

        int newIteration = iteration + 1;

        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 模型返回了 ToolCall，不设置 MODEL_COMPLETED，由 Router 决定走向
            Map<String, Object> result = new java.util.HashMap<>();
            result.put(MESSAGES, newMessages);
            result.put(PENDING_TOOL_CALLS, List.copyOf(toolCalls));
            result.put(LATEST_TOOL_RESULTS, List.of());
            result.put(ITERATION, newIteration);
            if (newSnapshot != null) {
                result.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                result.put(LATEST_CONTEXT_TRACE, newTrace);
            }
            if (memoryTrace != null) {
                result.put(LATEST_MEMORY_CONTEXT_TRACE, memoryTrace);
            }
            return result;
        }

        // 模型未返回 ToolCall
        String content = assistantMsg.content();
        if (content != null && !content.isBlank()) {
            Map<String, Object> result = new java.util.HashMap<>();
            result.put(MESSAGES, newMessages);
            result.put(PENDING_TOOL_CALLS, List.of());
            result.put(LATEST_TOOL_RESULTS, List.of());
            result.put(ITERATION, newIteration);
            result.put(STOP_REASON, ReactStopReason.MODEL_COMPLETED);
            if (newSnapshot != null) {
                result.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                result.put(LATEST_CONTEXT_TRACE, newTrace);
            }
            if (memoryTrace != null) {
                result.put(LATEST_MEMORY_CONTEXT_TRACE, memoryTrace);
            }
            return result;
        }

        // 模型既无有效文本也无 ToolCall
        log.error("Reason node received empty model response: runId={}, agent={}, iteration={}",
                runContext.runId(), definition.name(), iteration);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put(MESSAGES, newMessages);
        result.put(PENDING_TOOL_CALLS, List.of());
        result.put(LATEST_TOOL_RESULTS, List.of());
        result.put(ITERATION, newIteration);
        result.put(STOP_REASON, ReactStopReason.MODEL_ERROR);
        result.put(FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED);
        result.put(FAILURE_MESSAGE, "Model returned empty response");
        if (newSnapshot != null) {
            result.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
            result.put(LATEST_CONTEXT_TRACE, newTrace);
        }
        if (memoryTrace != null) {
            result.put(LATEST_MEMORY_CONTEXT_TRACE, memoryTrace);
        }
        return result;
    }

    /**
     * 加载当前用户的长期记忆上下文。
     * 记忆为空或 Provider 未注入时返回空上下文。
     * 记忆读取失败时使用明确框架错误，不得加载其他用户数据。
     */
    private LongTermMemoryContext loadMemoryContext(
            RunContext runContext
    ) {
        if (memoryContextProvider == null) {
            return LongTermMemoryContext.empty();
        }

        try {
            return memoryContextProvider.load(runContext.userId());
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to load long-term memory",
                    e);
        }
    }

    /**
     * 从 LongTermMemoryContext 解析临时上下文消息列表。
     */
    private List<AgentMessage> resolveEphemeralContext(LongTermMemoryContext memoryContext) {
        if (memoryContext.message().isPresent()) {
            return List.of(memoryContext.message().get());
        }
        return List.of();
    }

    /**
     * 从 LongTermMemoryContext 构建追踪。
     */
    private MemoryContextTrace buildMemoryTrace(
            LongTermMemoryContext memoryContext
    ) {
        if (memoryContextProvider == null) {
            return null;
        }

        return MemoryContextTrace.from(
                memoryContext,
                java.time.Instant.now());
    }
    /**
     * 将窗口状态合并到结果 Map 中。
     */
    private Map<String, Object> mergeContextState(Map<String, Object> base,
                                                   ContextWindowSnapshot snapshot,
                                                   ContextProcessingTrace trace) {
        Map<String, Object> result = new java.util.HashMap<>(base);
        if (snapshot != null) {
            result.put(CONTEXT_WINDOW_SNAPSHOT, snapshot);
            result.put(LATEST_CONTEXT_TRACE, trace);
        }
        return result;
    }

    /**
     * 将记忆追踪合并到结果 Map 中。
     */
    private Map<String, Object> mergeMemoryTrace(
            Map<String, Object> base,
            MemoryContextTrace memoryTrace
    ) {
        Map<String, Object> result =
                new java.util.HashMap<>(base);

        if (memoryTrace != null) {
            result.put(
                    LATEST_MEMORY_CONTEXT_TRACE,
                    memoryTrace);
        }

        return result;
    }

    private List<ToolDefinition> resolveAllowedTools(AgentDefinition definition) {
        if (definition.allowedTools() == null || definition.allowedTools().isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (String toolName : definition.allowedTools()) {
            if (!toolRegistry.contains(toolName)) {
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
