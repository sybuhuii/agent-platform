package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;

/**
 * Supervisor 状态 Key 常量和类型安全读取方法。
 * <p>
 * 禁止节点自行写字符串 key，统一通过此类访问。
 * 集合读取返回不可变快照，null 集合按空集合处理。
 */
public final class SupervisorStateKeys {

    private SupervisorStateKeys() {
    }

    // ---- 状态 Key 常量 ----

    public static final String SUPERVISOR_DEFINITION = "supervisorDefinition";
    public static final String ROOT_TASK = "rootTask";
    public static final String RUN_CONTEXT = "runContext";
    public static final String SUPERVISOR_MESSAGES = "supervisorMessages";
    public static final String DECISION = "decision";
    public static final String PENDING_TASKS = "pendingTasks";
    public static final String LATEST_AGENT_RESULTS = "latestAgentResults";
    public static final String AGENT_RESULTS = "agentResults";
    public static final String ITERATION = "iteration";
    public static final String FINAL_RESULT = "finalResult";
    public static final String STOP_REASON = "stopReason";
    public static final String FAILURE_ERROR_CODE = "failureErrorCode";
    public static final String FAILURE_MESSAGE = "failureMessage";

    // ---- Phase7 Batch4 上下文窗口 ----
    public static final String CONTEXT_WINDOW_SNAPSHOT = "contextWindowSnapshot";
    public static final String LATEST_CONTEXT_TRACE = "latestContextTrace";

    // ---- Phase8 Batch5 长期记忆上下文 ----
    public static final String LATEST_MEMORY_CONTEXT_TRACE = "latestMemoryContextTrace";

    // ---- Phase9 Batch2 Supervisor 暂停状态 ----
    public static final String RUN_STATUS = "runStatus";
    public static final String DISPATCH_TASKS = "dispatchTasks";
    public static final String SUSPENDED_CHILDREN = "suspendedChildren";
    public static final String CHECKPOINT_ID = "checkpointId";

    // ---- 类型安全读取方法 ----

    public static SupervisorDefinition getSupervisorDefinition(AgentState state) {
        return getRequired(state, SUPERVISOR_DEFINITION, SupervisorDefinition.class);
    }

    public static AgentTask getRootTask(AgentState state) {
        return getRequired(state, ROOT_TASK, AgentTask.class);
    }

    public static RunContext getRunContext(AgentState state) {
        return getRequired(state, RUN_CONTEXT, RunContext.class);
    }

    @SuppressWarnings("unchecked")
    public static List<AgentMessage> getSupervisorMessages(AgentState state) {
        return state.<List<AgentMessage>>value(SUPERVISOR_MESSAGES).orElse(List.of());
    }

    public static SupervisorDecision getDecision(AgentState state) {
        return state.<SupervisorDecision>value(DECISION).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public static List<AgentTask> getPendingTasks(AgentState state) {
        return state.<List<AgentTask>>value(PENDING_TASKS).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public static List<AgentResult> getLatestAgentResults(AgentState state) {
        return state.<List<AgentResult>>value(LATEST_AGENT_RESULTS).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public static List<AgentResult> getAgentResults(AgentState state) {
        return state.<List<AgentResult>>value(AGENT_RESULTS).orElse(List.of());
    }

    public static int getIteration(AgentState state) {
        return state.<Integer>value(ITERATION).orElse(0);
    }

    public static AgentResult getFinalResult(AgentState state) {
        return state.<AgentResult>value(FINAL_RESULT).orElse(null);
    }

    public static SupervisorStopReason getStopReason(AgentState state) {
        return state.<SupervisorStopReason>value(STOP_REASON).orElse(null);
    }

    public static AgentErrorCode getFailureErrorCode(AgentState state) {
        return state.<AgentErrorCode>value(FAILURE_ERROR_CODE).orElse(null);
    }

    public static String getFailureMessage(AgentState state) {
        return state.<String>value(FAILURE_MESSAGE).orElse(null);
    }

    // ---- Phase7 Batch4 上下文窗口访问器 ----

    /**
     * 获取 Supervisor 上下文窗口快照。初始为空。
     */
    public static com.ksyun.agent.runtime.context.ContextWindowSnapshot getContextWindowSnapshot(AgentState state) {
        return state.<com.ksyun.agent.runtime.context.ContextWindowSnapshot>value(CONTEXT_WINDOW_SNAPSHOT).orElse(null);
    }

    /**
     * 获取 Supervisor 最新上下文处理追踪。初始为空。
     */
    public static com.ksyun.agent.core.context.ContextProcessingTrace getLatestContextTrace(AgentState state) {
        return state.<com.ksyun.agent.core.context.ContextProcessingTrace>value(LATEST_CONTEXT_TRACE).orElse(null);
    }

    /**
     * 获取 Supervisor 最新长期记忆上下文追踪。初始为空。
     */
    public static com.ksyun.agent.runtime.memory.MemoryContextTrace getLatestMemoryContextTrace(AgentState state) {
        return state.<com.ksyun.agent.runtime.memory.MemoryContextTrace>value(LATEST_MEMORY_CONTEXT_TRACE).orElse(null);
    }

    // ---- Phase9 Batch2 Supervisor 暂停状态访问器 ----

    /**
     * 获取 Supervisor 运行状态。初始为空。
     */
    public static com.ksyun.agent.core.run.RunStatus getRunStatus(AgentState state) {
        return state.<com.ksyun.agent.core.run.RunStatus>value(RUN_STATUS).orElse(null);
    }

    /**
     * 获取当前分派批次的完整子任务执行状态表。初始为空列表。
     */
    @SuppressWarnings("unchecked")
    public static List<SupervisorChildExecution> getDispatchTasks(AgentState state) {
        return state.<List<SupervisorChildExecution>>value(DISPATCH_TASKS).orElse(List.of());
    }

    /**
     * 获取当前批次中暂停的子任务执行记录列表。初始为空列表。
     */
    @SuppressWarnings("unchecked")
    public static List<SupervisorChildExecution> getSuspendedChildren(AgentState state) {
        return state.<List<SupervisorChildExecution>>value(SUSPENDED_CHILDREN).orElse(List.of());
    }

    /**
     * 获取当前 Checkpoint ID。初始为空。
     */
    public static String getCheckpointId(AgentState state) {
        return state.<String>value(CHECKPOINT_ID).orElse(null);
    }

    private static <T> T getRequired(AgentState state, String key, Class<T> type) {
        T value = state.<T>value(key).orElse(null);
        if (value == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Required state key '" + key + "' is missing or null"
            );
        }
        if (!type.isInstance(value)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "State key '" + key + "' has wrong type: expected " + type.getName()
                            + ", got " + value.getClass().getName()
            );
        }
        return value;
    }
}
