package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.tool.ToolCall;
import com.ksyun.agent.core.tool.ToolResult;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * ReAct 状态 Key 常量和类型安全读取方法。
 * <p>
 * 禁止节点自行写字符串 key，统一通过此类访问。
 */
public final class ReactStateKeys {

    private ReactStateKeys() {
    }

    // ---- 原有 Key ----
    public static final String AGENT_DEFINITION = "agentDefinition";
    public static final String TASK = "task";
    public static final String RUN_CONTEXT = "runContext";
    public static final String MESSAGES = "messages";
    public static final String PENDING_TOOL_CALLS = "pendingToolCalls";
    public static final String LATEST_TOOL_RESULTS = "latestToolResults";
    public static final String TOOL_TRACES = "toolTraces";
    public static final String ITERATION = "iteration";
    public static final String FINAL_RESULT = "finalResult";
    public static final String STOP_REASON = "stopReason";
    public static final String FAILURE_MESSAGE = "failureMessage";
    public static final String FAILURE_ERROR_CODE = "failureErrorCode";

    // ---- 新增 Key（Phase6 Batch2）----
    public static final String TOOL_EXECUTION_CURSOR = "toolExecutionCursor";
    public static final String TOOL_EXECUTION_BUFFER = "toolExecutionBuffer";
    public static final String PENDING_APPROVAL = "pendingApproval";
    public static final String CHECKPOINT_ID = "checkpointId";
    public static final String RUN_STATUS = "runStatus";

    // ---- 新增 Key（Phase7 Batch4 上下文窗口）----
    public static final String CONTEXT_WINDOW_SNAPSHOT = "contextWindowSnapshot";
    public static final String LATEST_CONTEXT_TRACE = "latestContextTrace";

    // ---- 类型安全读取方法 ----

    public static AgentDefinition getAgentDefinition(AgentState state) {
        return getRequired(state, AGENT_DEFINITION, AgentDefinition.class);
    }

    public static AgentTask getTask(AgentState state) {
        return getRequired(state, TASK, AgentTask.class);
    }

    public static RunContext getRunContext(AgentState state) {
        return getRequired(state, RUN_CONTEXT, RunContext.class);
    }

    @SuppressWarnings("unchecked")
    public static List<AgentMessage> getMessages(AgentState state) {
        return state.<List<AgentMessage>>value(MESSAGES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public static List<ToolCall> getPendingToolCalls(AgentState state) {
        return state.<List<ToolCall>>value(PENDING_TOOL_CALLS).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public static List<ToolResult> getLatestToolResults(AgentState state) {
        return state.<List<ToolResult>>value(LATEST_TOOL_RESULTS).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public static List<ToolExecutionTrace> getToolTraces(AgentState state) {
        return state.<List<ToolExecutionTrace>>value(TOOL_TRACES).orElse(List.of());
    }

    public static int getIteration(AgentState state) {
        return state.<Integer>value(ITERATION).orElse(0);
    }

    public static AgentResult getFinalResult(AgentState state) {
        return state.<AgentResult>value(FINAL_RESULT).orElse(null);
    }

    public static ReactStopReason getStopReason(AgentState state) {
        return state.<ReactStopReason>value(STOP_REASON).orElse(null);
    }

    public static String getFailureMessage(AgentState state) {
        return state.<String>value(FAILURE_MESSAGE).orElse(null);
    }

    public static AgentErrorCode getFailureErrorCode(AgentState state) {
        return state.<AgentErrorCode>value(FAILURE_ERROR_CODE).orElse(null);
    }

    // ---- 新增 Key 类型安全读取方法 ----

    /**
     * 获取工具执行游标。初始为 0，中断时保持当前危险工具下标。
     */
    public static int getToolExecutionCursor(AgentState state) {
        return state.<Integer>value(TOOL_EXECUTION_CURSOR).orElse(0);
    }

    /**
     * 获取已完成但尚未 Observe 的 ToolResult 缓冲。
     */
    @SuppressWarnings("unchecked")
    public static List<ToolResult> getToolExecutionBuffer(AgentState state) {
        return state.<List<ToolResult>>value(TOOL_EXECUTION_BUFFER).orElse(List.of());
    }

    /**
     * 获取当前唯一审批项。普通运行为空。
     */
    public static PendingApproval getPendingApproval(AgentState state) {
        return state.<PendingApproval>value(PENDING_APPROVAL).orElse(null);
    }

    /**
     * 获取当前挂起 Checkpoint ID。普通运行为空。
     */
    public static String getCheckpointId(AgentState state) {
        return state.<String>value(CHECKPOINT_ID).orElse(null);
    }

    /**
     * 获取运行状态。
     */
    public static RunStatus getRunStatus(AgentState state) {
        return state.<RunStatus>value(RUN_STATUS).orElse(null);
    }

    // ---- Phase7 Batch4 上下文窗口访问器 ----

    /**
     * 获取上下文窗口快照。初始为空。
     */
    public static com.ksyun.agent.runtime.context.ContextWindowSnapshot getContextWindowSnapshot(AgentState state) {
        return state.<com.ksyun.agent.runtime.context.ContextWindowSnapshot>value(CONTEXT_WINDOW_SNAPSHOT).orElse(null);
    }

    /**
     * 获取最新上下文处理追踪。初始为空。
     */
    public static com.ksyun.agent.core.context.ContextProcessingTrace getLatestContextTrace(AgentState state) {
        return state.<com.ksyun.agent.core.context.ContextProcessingTrace>value(LATEST_CONTEXT_TRACE).orElse(null);
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
