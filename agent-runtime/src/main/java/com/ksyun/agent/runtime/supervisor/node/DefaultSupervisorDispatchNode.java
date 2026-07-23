package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor Dispatch 节点实现。
 * <p>
 * 按 pendingTasks 顺序逐个通过 ReactAgentEngine 执行子 Agent。
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultSupervisorDispatchNode implements SupervisorDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorDispatchNode.class);

    private final AgentRegistry agentRegistry;
    private final ReactAgentEngine reactAgentEngine;
    private final RunIdGenerator runIdGenerator;

    public DefaultSupervisorDispatchNode(AgentRegistry agentRegistry,
                                          ReactAgentEngine reactAgentEngine,
                                          RunIdGenerator runIdGenerator) {
        this.agentRegistry = agentRegistry;
        this.reactAgentEngine = reactAgentEngine;
        this.runIdGenerator = runIdGenerator;
    }

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        SupervisorDefinition definition = getSupervisorDefinition(state);
        RunContext parentContext = getRunContext(state);
        List<AgentTask> pendingTasks = getPendingTasks(state);

        if (pendingTasks.isEmpty()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "No pending tasks to dispatch"
            );
        }

        List<AgentResult> results = new ArrayList<>();

        for (AgentTask task : pendingTasks) {
            // 确认 agentName 属于 memberAgents
            if (!definition.memberAgents().contains(task.agentName())) {
                log.error("SupervisorDispatch: task agentName not in memberAgents: agentName={}", task.agentName());
                results.add(AgentResult.failure(task.agentName(),
                        AgentErrorCode.AGENT_NOT_FOUND.name(),
                        "Agent not in memberAgents: " + task.agentName()));
                continue;
            }

            AgentDefinition agentDef = agentRegistry.getRequired(task.agentName());

            // 创建独立子 RunContext
            String childRunId = runIdGenerator.nextRunId();
            String childThreadId = parentContext.threadId() + "-" + task.taskId();
            RunContext childContext = new RunContext(
                    parentContext.userId(),
                    parentContext.sessionId(),
                    childThreadId,
                    childRunId,
                    parentContext.roles(),
                    parentContext.permissions()
            );

            AgentResult result;
            try {
                result = reactAgentEngine.execute(agentDef, task, childContext);
            } catch (AgentFrameworkException e) {
                log.error("SupervisorDispatch: sub-agent execution failed: agentName={}, runId={}, errorCode={}",
                        task.agentName(), childRunId, e.getErrorCode());
                // 单个子Agent异常不破坏整个父图，转为失败AgentResult
                result = AgentResult.failure(task.agentName(),
                        e.getErrorCode().name(),
                        "Sub-agent execution failed");
            } catch (Exception e) {
                log.error("SupervisorDispatch: sub-agent unexpected error: agentName={}, runId={}",
                        task.agentName(), childRunId, e);
                result = AgentResult.failure(task.agentName(),
                        AgentErrorCode.INTERNAL_ERROR.name(),
                        "Sub-agent execution error");
            }

            // 补充非敏感 metadata
            Map<String, Object> meta = new HashMap<>();
            if (result.metadata() != null) {
                meta.putAll(result.metadata());
            }
            meta.put("parentRunId", parentContext.runId());
            meta.put("childRunId", childRunId);
            meta.put("childThreadId", childThreadId);
            meta.put("taskId", task.taskId());

            AgentResult enrichedResult = new AgentResult(
                    result.agentName(),
                    result.success(),
                    result.content(),
                    result.evidence(),
                    Collections.unmodifiableMap(meta),
                    result.errorCode()
            );

            results.add(enrichedResult);
        }

        // latestAgentResults 覆盖写入本轮完整结果列表
        return Map.of(
                LATEST_AGENT_RESULTS, List.copyOf(results)
        );
    }
}
