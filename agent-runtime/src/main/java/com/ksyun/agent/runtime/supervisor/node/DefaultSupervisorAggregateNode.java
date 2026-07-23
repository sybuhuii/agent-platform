package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.UserAgentMessage;
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

        // 校验
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

        // 覆盖清理：清空 pendingTasks、latestAgentResults、decision
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
        return updates;
    }
}
