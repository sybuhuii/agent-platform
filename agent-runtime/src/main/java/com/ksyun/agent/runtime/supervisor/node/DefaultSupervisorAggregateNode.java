package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorChildExecutionStatus;
import com.ksyun.agent.runtime.supervisor.SupervisorObservationFormatter;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor Aggregate 节点实现。
 * <p>
 * 汇总子 Agent 执行结果，将观察消息追加到 supervisorMessages。
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * Phase9 Batch2 新增：
 * - 聚合前校验所有任务都已达到可聚合终态（COMPLETED/FAILED）
 * - 拒绝包含 NOT_STARTED/RUNNING/SUSPENDED 的分派表
 * - 成功聚合后清理本轮临时状态
 */
public class DefaultSupervisorAggregateNode implements SupervisorAggregateNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorAggregateNode.class);

    private final SupervisorObservationFormatter formatter;

    public DefaultSupervisorAggregateNode(SupervisorObservationFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        List<AgentTask> pendingTasks = getPendingTasks(state);
        List<AgentResult> latestResults = getLatestAgentResults(state);
        List<SupervisorChildExecution> dispatchTasks = getDispatchTasks(state);
        List<SupervisorChildExecution> suspendedChildren = getSuspendedChildren(state);

        // 校验：不存在 SUSPENDED_CHILDREN
        if (suspendedChildren != null && !suspendedChildren.isEmpty()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Aggregate invoked with suspended children"
            );
        }

        // 校验：DISPATCH_TASKS 数量与 pendingTasks 一致
        if (dispatchTasks.size() != pendingTasks.size()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Dispatch tasks count mismatch with pending tasks"
            );
        }

        // 校验：不存在 NOT_STARTED / RUNNING / SUSPENDED
        for (SupervisorChildExecution exec : dispatchTasks) {
            if (exec.status() == SupervisorChildExecutionStatus.NOT_STARTED
                    || exec.status() == SupervisorChildExecutionStatus.RUNNING
                    || exec.status() == SupervisorChildExecutionStatus.SUSPENDED) {
                return Map.of(
                        FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                        STOP_REASON, SupervisorStopReason.INVALID_STATE,
                        FAILURE_MESSAGE, "Aggregate invoked with non-terminal child status: " + exec.status()
                );
            }
        }

        // 校验：实际结果与任务能够按 dispatchIndex 对齐
        if (pendingTasks.isEmpty() || latestResults.isEmpty()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Missing tasks or results for aggregation"
            );
        }
        if (pendingTasks.size() != latestResults.size()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Tasks and results count mismatch"
            );
        }

        // 追加到历史 agentResults
        List<AgentResult> newAgentResults = new ArrayList<>(latestResults);

        // 格式化观察消息（使用 UserAgentMessage，不使用 ToolAgentMessage）
        String observationContent = formatter.format(pendingTasks, latestResults);
        List<AgentMessage> observeMessages = new ArrayList<>();
        observeMessages.add(new UserAgentMessage(observationContent));

        // 成功聚合后清理本轮临时状态
        // 不得清理累计的 AGENT_RESULTS 和 Supervisor 消息历史
        // Use HashMap because Map.of() does not allow null values.
        // null value is the correct way to reset a Channel without defaultProvider —
        // Channel.update() treats null as MARK_FOR_RESET, and updateState() removes
        // entries with null values from the merged state.
        Map<String, Object> updates = new HashMap<>();
        updates.put(AGENT_RESULTS, newAgentResults);
        updates.put(SUPERVISOR_MESSAGES, observeMessages);
        updates.put(PENDING_TASKS, List.of());
        updates.put(LATEST_AGENT_RESULTS, List.of());
        updates.put(DECISION, null);
        updates.put(DISPATCH_TASKS, List.of());
        updates.put(SUSPENDED_CHILDREN, List.of());
        return updates;
    }
}
