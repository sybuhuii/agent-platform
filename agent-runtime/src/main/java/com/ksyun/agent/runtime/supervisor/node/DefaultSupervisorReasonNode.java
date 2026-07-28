package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.AssistantAgentMessage;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.context.ContextWindowManager;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.runtime.context.ContextWindowUpdate;
import com.ksyun.agent.runtime.model.ModelInvocationGateway;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.supervisor.SupervisorAction;
import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import com.ksyun.agent.runtime.supervisor.SupervisorDecision;
import com.ksyun.agent.runtime.supervisor.SupervisorDecisionDraft;
import com.ksyun.agent.runtime.supervisor.SupervisorDecisionParser;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import com.ksyun.agent.runtime.supervisor.SupervisorTaskDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor Reason 节点实现。
 * <p>
 * 调用模型获取结构化 SupervisorDecision。
 * 通过 ContextWindowManager 管理 Supervisor 自己的上下文窗口。
 * 父 Supervisor 拥有独立窗口，子 Agent 使用各自独立 ReAct 窗口。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultSupervisorReasonNode implements SupervisorReasonNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorReasonNode.class);

    private static final int MAX_TASKS_PER_ROUND = 10;
    private static final Set<String> RESERVED_CONTEXT_KEYS = Set.of(
            "userId", "sessionId", "threadId", "runId",
            "roles", "permissions", "systemPrompt", "allowedTools", "maxIterations"
    );

    private final ModelInvocationGateway modelGateway;
    private final SupervisorDecisionParser decisionParser;
    private final AgentRegistry agentRegistry;
    private final RunIdGenerator runIdGenerator;
    private final ContextWindowManager contextWindowManager;

    public DefaultSupervisorReasonNode(ModelInvocationGateway modelGateway,
                                        SupervisorDecisionParser decisionParser,
                                        AgentRegistry agentRegistry,
                                        RunIdGenerator runIdGenerator,
                                        ContextWindowManager contextWindowManager) {
        this.modelGateway = modelGateway;
        this.decisionParser = decisionParser;
        this.agentRegistry = agentRegistry;
        this.runIdGenerator = runIdGenerator;
        this.contextWindowManager = contextWindowManager;
    }

    @Override
    public Map<String, Object> apply(SupervisorAgentState state) throws Exception {
        SupervisorDefinition definition = getSupervisorDefinition(state);
        RunContext runContext = getRunContext(state);
        List<AgentMessage> messages = getSupervisorMessages(state);
        int iteration = getIteration(state);

        // 若 iteration 已达上限，不再调用模型
        if (iteration >= definition.maxIterations()) {
            return Map.of(
                    STOP_REASON, SupervisorStopReason.MAX_ITERATIONS_REACHED,
                    FAILURE_ERROR_CODE, AgentErrorCode.MAX_ITERATIONS_REACHED,
                    FAILURE_MESSAGE, "Supervisor reached max iterations without completion"
            );
        }

        // 读取 Supervisor 自己的上下文窗口
        ContextWindowSnapshot previousSnapshot = getContextWindowSnapshot(state);
        Optional<ContextWindowSnapshot> previousOpt = Optional.ofNullable(previousSnapshot);

        // 确定发送给模型的消息
        List<AgentMessage> modelMessages;
        ContextWindowSnapshot newSnapshot = null;
        ContextProcessingTrace newTrace = null;

        Optional<ContextWindowUpdate> windowUpdate = contextWindowManager.update(messages, previousOpt);
        if (windowUpdate.isPresent()) {
            ContextWindowUpdate update = windowUpdate.get();
            modelMessages = update.modelMessages();
            newSnapshot = update.snapshot();
            newTrace = update.trace();
        } else {
            // 上下文关闭，使用完整消息
            modelMessages = messages;
        }

        // 构造 ModelRequest：tools 必须为空
        ModelRequest request = new ModelRequest(modelMessages, List.of(), Map.of());

        // 调用模型
        ModelResponse response;
        try {
            response = modelGateway.invoke(request, runContext);
        } catch (Exception e) {
            log.error("SupervisorReason model invocation failed: runId={}, supervisor={}, iteration={}",
                    runContext.runId(), definition.name(), iteration, e);
            Map<String, Object> errorResult = Map.of(
                    STOP_REASON, SupervisorStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                    FAILURE_MESSAGE, "Supervisor model invocation failed"
            );
            if (newSnapshot != null) {
                Map<String, Object> merged = new java.util.HashMap<>(errorResult);
                merged.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                merged.put(LATEST_CONTEXT_TRACE, newTrace);
                return merged;
            }
            return errorResult;
        }

        // 校验模型响应
        AssistantAgentMessage assistantMsg = response.message();
        if (assistantMsg == null) {
            Map<String, Object> errorResult = Map.of(
                    STOP_REASON, SupervisorStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                    FAILURE_MESSAGE, "Supervisor model returned empty message"
            );
            if (newSnapshot != null) {
                Map<String, Object> merged = new java.util.HashMap<>(errorResult);
                merged.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                merged.put(LATEST_CONTEXT_TRACE, newTrace);
                return merged;
            }
            return errorResult;
        }

        // 模型返回 ToolCall → 视为失败
        if (assistantMsg.toolCalls() != null && !assistantMsg.toolCalls().isEmpty()) {
            log.error("SupervisorReason model returned ToolCall: runId={}, supervisor={}", runContext.runId(), definition.name());
            Map<String, Object> errorResult = Map.of(
                    STOP_REASON, SupervisorStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                    FAILURE_MESSAGE, "Supervisor model must not return tool calls"
            );
            if (newSnapshot != null) {
                Map<String, Object> merged = new java.util.HashMap<>(errorResult);
                merged.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                merged.put(LATEST_CONTEXT_TRACE, newTrace);
                return merged;
            }
            return errorResult;
        }

        String content = assistantMsg.content();
        if (content == null || content.isBlank()) {
            Map<String, Object> errorResult = Map.of(
                    STOP_REASON, SupervisorStopReason.MODEL_ERROR,
                    FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED,
                    FAILURE_MESSAGE, "Supervisor model returned empty content"
            );
            if (newSnapshot != null) {
                Map<String, Object> merged = new java.util.HashMap<>(errorResult);
                merged.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                merged.put(LATEST_CONTEXT_TRACE, newTrace);
                return merged;
            }
            return errorResult;
        }

        // 追加 assistant 消息
        List<AgentMessage> newMessages = new ArrayList<>();
        newMessages.add(assistantMsg);
        int newIteration = iteration + 1;

        // 解析决策
        SupervisorDecisionDraft draft;
        try {
            draft = decisionParser.parse(content);
        } catch (AgentFrameworkException e) {
            log.error("SupervisorReason decision parse failed: runId={}, supervisor={}, iteration={}",
                    runContext.runId(), definition.name(), iteration);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put(SUPERVISOR_MESSAGES, newMessages);
            result.put(ITERATION, newIteration);
            result.put(STOP_REASON, SupervisorStopReason.MODEL_ERROR);
            result.put(FAILURE_ERROR_CODE, AgentErrorCode.MODEL_INVOCATION_FAILED);
            result.put(FAILURE_MESSAGE, "Supervisor decision parse failed");
            if (newSnapshot != null) {
                result.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                result.put(LATEST_CONTEXT_TRACE, newTrace);
            }
            return result;
        }

        // 根据决策动作校验和规范化
        try {
            Map<String, Object> decisionResult;
            if (draft.action() == SupervisorAction.DISPATCH) {
                decisionResult = handleDispatch(definition, draft, newMessages, newIteration);
            } else {
                decisionResult = handleFinish(draft, newMessages, newIteration);
            }
            // 合并上下文窗口状态
            if (newSnapshot != null) {
                Map<String, Object> merged = new java.util.HashMap<>(decisionResult);
                merged.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                merged.put(LATEST_CONTEXT_TRACE, newTrace);
                return merged;
            }
            return decisionResult;
        } catch (AgentFrameworkException e) {
            log.error("SupervisorReason decision validation failed: runId={}, supervisor={}, iteration={}, errorCode={}",
                    runContext.runId(), definition.name(), iteration, e.getErrorCode());
            Map<String, Object> result = new java.util.HashMap<>();
            result.put(SUPERVISOR_MESSAGES, newMessages);
            result.put(ITERATION, newIteration);
            result.put(STOP_REASON, SupervisorStopReason.MODEL_ERROR);
            result.put(FAILURE_ERROR_CODE, e.getErrorCode());
            result.put(FAILURE_MESSAGE, e.getMessage());
            if (newSnapshot != null) {
                result.put(CONTEXT_WINDOW_SNAPSHOT, newSnapshot);
                result.put(LATEST_CONTEXT_TRACE, newTrace);
            }
            return result;
        }
    }

    private Map<String, Object> handleDispatch(SupervisorDefinition definition,
                                                SupervisorDecisionDraft draft,
                                                List<AgentMessage> newMessages,
                                                int newIteration) {
        List<SupervisorTaskDraft> taskDrafts = draft.tasks();
        if (taskDrafts.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "DISPATCH requires at least one task");
        }
        if (taskDrafts.size() > MAX_TASKS_PER_ROUND) {
            throw new AgentFrameworkException(AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "DISPATCH tasks exceed maximum limit");
        }

        List<AgentTask> tasks = new ArrayList<>();
        for (SupervisorTaskDraft td : taskDrafts) {
            if (td.agentName() == null || td.agentName().isBlank()) {
                throw new AgentFrameworkException(AgentErrorCode.MODEL_INVOCATION_FAILED,
                        "Task agentName must not be blank");
            }
            if (!definition.memberAgents().contains(td.agentName())) {
                throw new AgentFrameworkException(AgentErrorCode.AGENT_NOT_FOUND,
                        "Task agentName not in memberAgents: " + td.agentName());
            }
            if (!agentRegistry.contains(td.agentName())) {
                throw new AgentFrameworkException(AgentErrorCode.AGENT_NOT_FOUND,
                        "Agent not found in registry: " + td.agentName());
            }
            if (td.instruction() == null || td.instruction().isBlank()) {
                throw new AgentFrameworkException(AgentErrorCode.MODEL_INVOCATION_FAILED,
                        "Task instruction must not be blank");
            }
            Map<String, Object> safeContext = filterReservedKeys(td.context());
            String taskId = "sup-task-" + runIdGenerator.nextRunId();
            tasks.add(new AgentTask(taskId, td.agentName(), td.instruction(), safeContext));
        }

        SupervisorDecision decision = new SupervisorDecision(
                SupervisorAction.DISPATCH,
                List.copyOf(tasks),
                draft.decisionSummary(),
                null
        );

        return Map.of(
                SUPERVISOR_MESSAGES, newMessages,
                ITERATION, newIteration,
                DECISION, decision,
                PENDING_TASKS, List.copyOf(tasks),
                LATEST_AGENT_RESULTS, List.of()
        );
    }

    private Map<String, Object> handleFinish(SupervisorDecisionDraft draft,
                                               List<AgentMessage> newMessages,
                                               int newIteration) {
        if (!draft.tasks().isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "FINISH requires empty tasks");
        }
        if (draft.finalAnswer() == null || draft.finalAnswer().isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "FINISH requires non-blank finalAnswer");
        }

        SupervisorDecision decision = new SupervisorDecision(
                SupervisorAction.FINISH,
                List.of(),
                draft.decisionSummary(),
                draft.finalAnswer()
        );

        return Map.of(
                SUPERVISOR_MESSAGES, newMessages,
                ITERATION, newIteration,
                DECISION, decision,
                PENDING_TASKS, List.of(),
                LATEST_AGENT_RESULTS, List.of(),
                STOP_REASON, SupervisorStopReason.COMPLETED
        );
    }

    private Map<String, Object> filterReservedKeys(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (!RESERVED_CONTEXT_KEYS.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(filtered);
    }
}
