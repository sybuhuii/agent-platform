package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStopReason;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 挂起节点。
 * <p>
 * 当 STOP_REASON == SUSPENDED 或 RunStatus == SUSPENDED 时执行此节点。
 * <p>
 * 约束：
 * - 不调用模型
 * - 不调用工具
 * - 不保存、修改或删除 Checkpoint
 * - 只构造挂起 AgentResult
 * - 完成后进入 END
 * - 不进入 Observe
 * - 不进入 Failure
 */
public class DefaultReactSuspendNode implements NodeAction<ReactAgentState> {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactSuspendNode.class);

    @Override
    public Map<String, Object> apply(ReactAgentState state) {
        RunContext runContext = getRunContext(state);
        var definition = getAgentDefinition(state);
        PendingApproval approval = getPendingApproval(state);

        if (runContext == null) {
            log.error("Suspend node invoked without RunContext");
            return Map.of(
                    FINAL_RESULT, AgentResult.failure("unknown",
                            "INTERNAL_ERROR", "Suspend node invoked without RunContext")
            );
        }

        String agentName = definition != null ? definition.name() : "unknown";

        // 构造挂起的 AgentResult
        AgentResult suspendedResult;
        if (approval != null) {
            suspendedResult = AgentResult.suspended(
                    agentName,
                    approval.approvalId(),
                    approval.payload().operationName(),
                    approval.payload().riskLevel().name(),
                    approval.payload().requestedAt().toString()
            );
        } else {
            // 没有 approval 信息时的兜底
            suspendedResult = AgentResult.suspended(
                    agentName,
                    "unknown",
                    "unknown",
                    "HIGH",
                    "unknown"
            );
        }

        log.info("Agent suspended: agentName={}, runId={}, approvalId={}",
                agentName, runContext.runId(),
                approval != null ? approval.approvalId() : "unknown");

        return Map.of(
                FINAL_RESULT, suspendedResult,
                STOP_REASON, ReactStopReason.SUSPENDED
        );
    }
}
