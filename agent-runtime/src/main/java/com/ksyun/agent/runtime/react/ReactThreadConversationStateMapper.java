package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolResult;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ksyun.agent.runtime.context.ContextWindowSnapshotRestorer;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReactAgentState 与 ThreadConversationState 之间的映射器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 根据新任务构建新的 ReactAgentState
 * 2. 根据已有 ThreadConversationState 构建续接 ReactAgentState
 * 3. 从执行结束后的 ReactAgentState 提取 ThreadConversationState
 * 4. 集中清理上一轮临时运行字段
 * <p>
 * 不访问 CheckpointStore。不执行模型或工具。
 * 不把映射逻辑散落到 Application Service 和 Controller。
 */
public class ReactThreadConversationStateMapper {

    private static final Logger log = LoggerFactory.getLogger(ReactThreadConversationStateMapper.class);

    private final Clock clock;

    public ReactThreadConversationStateMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 根据新任务构建新的 ReactAgentState。
     * <p>
     * 保持当前 ReactAgentEngine 既有行为：
     * - 创建 Agent 规定的 System 消息
     * - 追加本次 User 消息
     * - 设置当前 RunContext
     * - 设置当前 AgentDefinition 和必要标识
     * - iteration 初始化为 0
     * - status 初始化为运行状态
     * - pendingToolCalls、toolResults、executionBuffer、pendingApproval、finalResult 为空
     * - ContextWindowSnapshot、latestContextTrace 为空
     * - 使用当前 runId 和 threadId
     * <p>
     * 不写入 MemoryStore。不读取旧 Checkpoint。
     */
    public ReactAgentState createInitialState(
            AgentDefinition definition,
            AgentTask task,
            RunContext runContext
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(runContext, "runContext must not be null");

        // 构造初始 messages
        List<AgentMessage> initialMessages = new ArrayList<>();
        if (definition.systemPrompt() != null && !definition.systemPrompt().isBlank()) {
            initialMessages.add(new SystemAgentMessage(definition.systemPrompt()));
        }
        initialMessages.add(new UserAgentMessage(task.instruction()));

        // 构造初始 State
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(AGENT_DEFINITION, definition);
        initialState.put(TASK, task);
        initialState.put(RUN_CONTEXT, runContext);
        initialState.put(MESSAGES, initialMessages);
        initialState.put(PENDING_TOOL_CALLS, List.of());
        initialState.put(LATEST_TOOL_RESULTS, List.of());
        initialState.put(TOOL_TRACES, List.of());
        initialState.put(ITERATION, 0);
        initialState.put(FINAL_RESULT, null);
        initialState.put(STOP_REASON, null);
        initialState.put(FAILURE_MESSAGE, null);
        initialState.put(FAILURE_ERROR_CODE, null);
        // Phase6 Batch2 新增初始状态
        initialState.put(TOOL_EXECUTION_CURSOR, 0);
        initialState.put(TOOL_EXECUTION_BUFFER, List.of());
        initialState.put(PENDING_APPROVAL, null);
        initialState.put(CHECKPOINT_ID, null);
        initialState.put(RUN_STATUS, null);
        // Phase7 Batch4 上下文窗口
        initialState.put(CONTEXT_WINDOW_SNAPSHOT, null);
        initialState.put(LATEST_CONTEXT_TRACE, null);

        log.debug("Created initial state: runId={}, threadId={}, agentName={}, messageCount={}",
                runContext.runId(), runContext.threadId(), definition.name(), initialMessages.size());

        return new ReactAgentState(initialState);
    }

    /**
     * 根据已有 ThreadConversationState 构建续接 ReactAgentState。
     * <p>
     * 必须执行：
     * - 校验 previousState.executionType 为 REACT_AGENT
     * - 校验 previousState.participantName 等于当前 agentName
     * - 读取 previousState.messages
     * - 复制完整消息列表，不修改 previousState.messages
     * - 不重复插入 System 消息
     * - 追加当前 AgentTask 中的 User 消息
     * - 设置新的 RunContext
     * - 设置新的 runId
     * - 保持原 threadId
     * - 设置新的 taskId
     * - iteration 重置为 0
     * - status 重置为运行状态
     * - pendingToolCalls、toolResults、executionBuffer、pendingApproval 清空
     * - currentToolCall、executionCursor、finalResult、failure 信息清空
     * - 恢复 previousState.contextWindowSnapshot
     * - 恢复 previousState.latestContextTrace
     * <p>
     * 不恢复上一轮 Tool 调用游标。不重新执行上一轮工具。
     * 不恢复上一轮 Assistant 最终结果为当前 finalResult。
     * 不恢复上一轮 RunContext。不恢复上一轮 sessionId。
     * 当前权限必须来自当前已验证 Session。
     */
    public ReactAgentState createContinuedState(
            AgentDefinition definition,
            AgentTask task,
            RunContext runContext,
            ThreadConversationState previousState
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(runContext, "runContext must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");

        // 校验 previousState.executionType 为 REACT_AGENT
        if (previousState.executionType() != CheckpointExecutionType.REACT_AGENT) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "ExecutionType mismatch: expected REACT_AGENT, got " + previousState.executionType());
        }

        // 校验 previousState.participantName 等于当前 agentName
        if (!definition.name().equals(previousState.participantName())) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "ParticipantName mismatch: expected " + definition.name()
                            + ", stored " + previousState.participantName());
        }

        // THREAD_MEMORY 已过滤 SystemAgentMessage，续接时根据当前 Definition 重新创建一条 System 消息，
        // 再追加持久化的非 System 历史，最后追加本次 User 消息
        List<AgentMessage> continuedMessages = new ArrayList<>(previousState.messages().size() + 2);
        if (definition.systemPrompt() != null && !definition.systemPrompt().isBlank()) {
            continuedMessages.add(new SystemAgentMessage(definition.systemPrompt()));
        }
        // 防御性过滤：确保历史不混入 System/MemoryContext（payload 已过滤，此处双保险）
        for (AgentMessage msg : previousState.messages()) {
            if (!(msg instanceof SystemAgentMessage) && !(msg instanceof MemoryContextAgentMessage)) {
                continuedMessages.add(msg);
            }
        }
        // 追加当前 AgentTask 中的 User 消息
        continuedMessages.add(new UserAgentMessage(task.instruction()));

        // 构造续接 State
        Map<String, Object> continuedState = new HashMap<>();
        continuedState.put(AGENT_DEFINITION, definition);
        continuedState.put(TASK, task);
        continuedState.put(RUN_CONTEXT, runContext);
        continuedState.put(MESSAGES, continuedMessages);
        // 临时运行字段全部重置/清空
        continuedState.put(PENDING_TOOL_CALLS, List.of());
        continuedState.put(LATEST_TOOL_RESULTS, List.of());
        continuedState.put(TOOL_TRACES, List.of());
        continuedState.put(ITERATION, 0);
        continuedState.put(FINAL_RESULT, null);
        continuedState.put(STOP_REASON, null);
        continuedState.put(FAILURE_MESSAGE, null);
        continuedState.put(FAILURE_ERROR_CODE, null);
        continuedState.put(TOOL_EXECUTION_CURSOR, 0);
        continuedState.put(TOOL_EXECUTION_BUFFER, List.of());
        continuedState.put(PENDING_APPROVAL, null);
        continuedState.put(CHECKPOINT_ID, null);
        continuedState.put(RUN_STATUS, null);

        ContextWindowSnapshot restoredSnapshot = previousState.contextWindowSnapshot()
                .map(snapshot -> ContextWindowSnapshotRestorer.forThreadContinuation(
                        snapshot,
                        definition.systemPrompt()))
                .orElse(null);

        continuedState.put(CONTEXT_WINDOW_SNAPSHOT, restoredSnapshot);
        continuedState.put(LATEST_CONTEXT_TRACE,
                previousState.latestContextTrace().orElse(null));

        log.debug("Created continued state: runId={}, threadId={}, agentName={}, " +
                        "previousMessageCount={}, totalMessageCount={}",
                runContext.runId(), runContext.threadId(), definition.name(),
                previousState.messages().size(), continuedMessages.size());

        return new ReactAgentState(continuedState);
    }

    /**
     * 从最终 ReactAgentState 提取 ThreadConversationState。
     * <p>
     * 提取字段：
     * - executionType=REACT_AGENT
     * - participantName=agentName
     * - 完整 messages（不是 processedMessages）
     * - contextWindowSnapshot
     * - latestContextTrace
     * - lastCompletedRunId=runId
     * - updatedAt
     * <p>
     * 不包含 pendingToolCalls、tool execution buffer、pendingApproval、
     * executionCursor、failure 异常、RunContext、Session、permissions、
     * finalResult 对象、Spring AI Message。
     * <p>
     * 提取前确认消息历史不存在未完成 ToolCall。
     * 提取前确认没有 PendingApproval。
     * 提取前确认当前状态属于稳定终态。
     * 不稳定状态抛 THREAD_CHECKPOINT_INVALID。
     * 不自动伪造缺失 ToolResult。
     */
    public ThreadConversationState extractStableState(
            String agentName,
            String runId,
            ReactAgentState finalState,
            Instant updatedAt
    ) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // 确认没有 PendingApproval
        PendingApproval pending = ReactStateKeys.getPendingApproval(finalState);
        if (pending != null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: pendingApproval exists");
        }

        // 确认消息历史不存在未完成 ToolCall
        List<ToolCall> pendingToolCalls = ReactStateKeys.getPendingToolCalls(finalState);
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: pendingToolCalls exist");
        }

        // 确认 executionBuffer 为空
        List<ToolResult> executionBuffer = ReactStateKeys.getToolExecutionBuffer(finalState);
        if (executionBuffer != null && !executionBuffer.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: toolExecutionBuffer is not empty");
        }

        // 确认 executionCursor 为 0
        int cursor = ReactStateKeys.getToolExecutionCursor(finalState);
        if (cursor != 0) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: toolExecutionCursor is not 0");
        }

        // 读取完整 messages（不是 processedMessages）
        List<AgentMessage> messages = ReactStateKeys.getMessages(finalState);
        if (messages == null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: messages is null");
        }

        // 持久化安全：THREAD_MEMORY 不持久化 SystemAgentMessage（恢复时由当前 Definition 重建）
        // 和 MemoryContextAgentMessage（下次 Reason 重新注入）
        List<AgentMessage> safeMessages = filterSafeMessages(messages);

        // 读取 ContextWindowSnapshot
        ContextWindowSnapshot snapshot = ReactStateKeys.getContextWindowSnapshot(finalState);
        ContextProcessingTrace trace = ReactStateKeys.getLatestContextTrace(finalState);

        return new ThreadConversationState(
                CheckpointExecutionType.REACT_AGENT,
                agentName,
                safeMessages,
                Optional.ofNullable(snapshot),
                Optional.ofNullable(trace),
                runId,
                updatedAt
        );
    }

    /**
     * 过滤消息列表，只保留可持久化类型：
     * UserAgentMessage、AssistantAgentMessage、ToolAgentMessage、SummaryAgentMessage。
     * <p>
     * 排除：SystemAgentMessage（恢复时由当前 Definition 重建）、
     * MemoryContextAgentMessage（下次 Reason 重新注入）。
     */
    private static List<AgentMessage> filterSafeMessages(List<AgentMessage> messages) {
        List<AgentMessage> filtered = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if (!(msg instanceof SystemAgentMessage) && !(msg instanceof MemoryContextAgentMessage)) {
                filtered.add(msg);
            }
        }
        return List.copyOf(filtered);
    }
}
