package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * SupervisorAgentState 与 ThreadConversationState 之间的映射器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 构建新 Supervisor 线程的初始 State
 * 2. 根据已有 ThreadConversationState 构建续接 State
 * 3. 从稳定完成的 SupervisorAgentState 提取 ThreadConversationState
 * 4. 清理上一轮 Supervisor 临时运行字段
 * <p>
 * 不访问 CheckpointStore。不执行模型。不调用子 Agent。不依赖 Spring。
 * 不得把初始化和提取逻辑散落到 Controller 及 Application Service。
 */
public class SupervisorThreadConversationStateMapper {

    private static final Logger log = LoggerFactory.getLogger(SupervisorThreadConversationStateMapper.class);

    private final SupervisorPromptBuilder promptBuilder;
    private final Clock clock;

    public SupervisorThreadConversationStateMapper(SupervisorPromptBuilder promptBuilder, Clock clock) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 根据新任务构建新的 SupervisorAgentState。
     * <p>
     * 保持当前 SupervisorEngine 既有行为：
     * - 创建 Supervisor 规定的 System 消息
     * - 追加当前 User 消息
     * - 设置当前 RunContext
     * - 设置当前 SupervisorDefinition 或必要标识
     * - iteration 初始化为 0
     * - status 初始化为运行状态
     * - currentDecision 为空
     * - dispatchTarget 为空
     * - subAgentResults 为空
     * - aggregateBuffer 为空
     * - finalResult 为空
     * - failure 信息为空
     * - ContextWindowSnapshot 为空
     * - latestContextTrace 为空
     * - 使用当前 runId 和 threadId
     * <p>
     * 不访问 MemoryStore。不读取旧 Checkpoint。不提前创建子 Agent 状态。
     */
    public SupervisorAgentState createInitialState(
            SupervisorDefinition definition,
            AgentTask task,
            RunContext runContext
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(runContext, "runContext must not be null");

        // 构造系统提示词
        String systemPrompt = promptBuilder.build(definition);

        // 构造初始 supervisorMessages
        List<AgentMessage> initialMessages = new ArrayList<>();
        initialMessages.add(new SystemAgentMessage(systemPrompt));
        initialMessages.add(new UserAgentMessage(task.instruction()));

        // 构造初始 State
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(SUPERVISOR_DEFINITION, definition);
        initialState.put(ROOT_TASK, task);
        initialState.put(RUN_CONTEXT, runContext);
        initialState.put(SUPERVISOR_MESSAGES, initialMessages);
        initialState.put(DECISION, null);
        initialState.put(PENDING_TASKS, List.of());
        initialState.put(LATEST_AGENT_RESULTS, List.of());
        initialState.put(AGENT_RESULTS, List.of());
        initialState.put(ITERATION, 0);
        initialState.put(FINAL_RESULT, null);
        initialState.put(STOP_REASON, null);
        initialState.put(FAILURE_MESSAGE, null);
        initialState.put(FAILURE_ERROR_CODE, null);
        // Phase7 Batch4 上下文窗口
        initialState.put(CONTEXT_WINDOW_SNAPSHOT, null);
        initialState.put(LATEST_CONTEXT_TRACE, null);

        log.debug("Created initial Supervisor state: runId={}, threadId={}, supervisor={}, messageCount={}",
                runContext.runId(), runContext.threadId(), definition.name(), initialMessages.size());

        return new SupervisorAgentState(initialState);
    }

    /**
     * 根据已有 ThreadConversationState 构建续接 SupervisorAgentState。
     * <p>
     * 必须执行：
     * - 校验 previousState.executionType 为 SUPERVISOR
     * - 校验 previousState.participantName 等于当前 supervisorName
     * - 复制上一轮完整 messages，不修改 previousState.messages
     * - 不重复插入 System 消息
     * - 追加当前一条 User 消息
     * - 设置新的 RunContext
     * - 使用新的 runId，保持原 threadId
     * - 使用新的 taskId
     * - iteration 重置为 0
     * - status 重置为运行状态
     * - currentDecision 清空
     * - dispatchTarget 清空
     * - subAgentResults 清空
     * - aggregateBuffer 清空
     * - currentSubAgent 清空
     * - pendingDispatch 清空
     * - finalResult 清空
     * - failure 信息清空
     * - 恢复 previousState.contextWindowSnapshot
     * - 恢复 previousState.latestContextTrace
     * <p>
     * 不恢复上一轮成员子 Agent 的 ReactAgentState。
     * 不恢复上一轮子 Agent 的 pendingToolCalls。
     * 不恢复上一轮子 Agent 的 ContextWindowSnapshot。
     * 不恢复上一轮 RunContext。不恢复上一轮 Session ID。
     * 当前用户身份和权限必须来自当前已验证 Session。
     * 不重新执行上一轮子 Agent 调用。
     */
    public SupervisorAgentState createContinuedState(
            SupervisorDefinition definition,
            AgentTask task,
            RunContext runContext,
            ThreadConversationState previousState
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(runContext, "runContext must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");

        // 校验 previousState.executionType 为 SUPERVISOR
        if (previousState.executionType() != CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "ExecutionType mismatch: expected SUPERVISOR, got " + previousState.executionType());
        }

        // 校验 previousState.participantName 等于当前 supervisorName
        if (!definition.name().equals(previousState.participantName())) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "ParticipantName mismatch: expected " + definition.name()
                            + ", stored " + previousState.participantName());
        }

        // 复制上一轮完整 messages，不修改 previousState.messages
        List<AgentMessage> continuedMessages = new ArrayList<>(previousState.messages());

        // 不重复插入 System 消息——保留上一轮完整历史中的原 System 消息
        // 追加当前一条 User 消息
        continuedMessages.add(new UserAgentMessage(task.instruction()));

        // 构造续接 State
        Map<String, Object> continuedState = new HashMap<>();
        continuedState.put(SUPERVISOR_DEFINITION, definition);
        continuedState.put(ROOT_TASK, task);
        continuedState.put(RUN_CONTEXT, runContext);
        continuedState.put(SUPERVISOR_MESSAGES, continuedMessages);
        // 临时运行字段全部重置/清空
        continuedState.put(DECISION, null);
        continuedState.put(PENDING_TASKS, List.of());
        continuedState.put(LATEST_AGENT_RESULTS, List.of());
        continuedState.put(AGENT_RESULTS, List.of());
        continuedState.put(ITERATION, 0);
        continuedState.put(FINAL_RESULT, null);
        continuedState.put(STOP_REASON, null);
        continuedState.put(FAILURE_MESSAGE, null);
        continuedState.put(FAILURE_ERROR_CODE, null);

        // 恢复 previousState.contextWindowSnapshot
        continuedState.put(CONTEXT_WINDOW_SNAPSHOT,
                previousState.contextWindowSnapshot().orElse(null));
        // 恢复 previousState.latestContextTrace
        continuedState.put(LATEST_CONTEXT_TRACE,
                previousState.latestContextTrace().orElse(null));

        log.debug("Created continued Supervisor state: runId={}, threadId={}, supervisor={}, " +
                        "previousMessageCount={}, totalMessageCount={}",
                runContext.runId(), runContext.threadId(), definition.name(),
                previousState.messages().size(), continuedMessages.size());

        return new SupervisorAgentState(continuedState);
    }

    /**
     * 从稳定完成的 SupervisorAgentState 提取 ThreadConversationState。
     * <p>
     * 提取字段：
     * - executionType=SUPERVISOR
     * - participantName=supervisorName
     * - 完整 messages（不是 ContextWindowSnapshot.windowMessages）
     * - contextWindowSnapshot
     * - latestContextTrace
     * - lastCompletedRunId
     * - updatedAt
     * <p>
     * 不保存 currentDecision、dispatchTarget、subAgentResults 临时集合、
     * aggregateBuffer、当前子 Agent 运行 State、RunContext、Session、
     * permissions、failure 异常、finalResult 对象、CompiledGraph、
     * Spring AI Message、MemoryEntry。
     * <p>
     * 消息历史必须合法。不得存在未完成的父级路由状态。
     * 提取结果必须不可变。
     */
    public ThreadConversationState extractStableState(
            String supervisorName,
            String runId,
            SupervisorAgentState finalState,
            Instant updatedAt
    ) {
        Objects.requireNonNull(supervisorName, "supervisorName must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // 读取完整 messages
        List<AgentMessage> messages = SupervisorStateKeys.getSupervisorMessages(finalState);
        if (messages == null || messages.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: supervisorMessages is null or empty");
        }

        // 确认没有未完成的父级路由状态
        SupervisorDecision decision = SupervisorStateKeys.getDecision(finalState);
        if (decision != null && decision.action() == SupervisorAction.DISPATCH) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Cannot extract stable state: DISPATCH decision still pending");
        }

        // 读取 ContextWindowSnapshot
        ContextWindowSnapshot snapshot = SupervisorStateKeys.getContextWindowSnapshot(finalState);
        ContextProcessingTrace trace = SupervisorStateKeys.getLatestContextTrace(finalState);

        return new ThreadConversationState(
                CheckpointExecutionType.SUPERVISOR,
                supervisorName,
                messages,
                Optional.ofNullable(snapshot),
                Optional.ofNullable(trace),
                runId,
                updatedAt
        );
    }
}
