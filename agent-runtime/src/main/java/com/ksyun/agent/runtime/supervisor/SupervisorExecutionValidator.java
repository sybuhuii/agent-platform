package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.registry.AgentRegistry;

import java.util.Set;

/**
 * Supervisor 运行请求校验器，纯 Java 实现。
 * <p>
 * 不调用模型，不执行 Agent，不修改输入对象。
 */
public class SupervisorExecutionValidator {

    private final AgentRegistry agentRegistry;

    public SupervisorExecutionValidator(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * 校验 Supervisor 执行请求参数。
     *
     * @param definition Supervisor 定义
     * @param rootTask    根任务
     * @param context     运行上下文
     */
    public void validate(SupervisorDefinition definition, AgentTask rootTask, RunContext context) {
        if (definition == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "SupervisorDefinition must not be null");
        }
        if (rootTask == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "rootTask must not be null");
        }
        if (context == null) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "RunContext must not be null");
        }

        // rootTask.agentName 与 SupervisorDefinition.name 一致
        if (rootTask.agentName() != null && !rootTask.agentName().isBlank()
                && !rootTask.agentName().equals(definition.name())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "rootTask.agentName must match SupervisorDefinition.name: expected "
                            + definition.name() + ", got " + rootTask.agentName()
            );
        }

        if (definition.maxIterations() <= 0) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "maxIterations must be greater than 0");
        }

        if (rootTask.instruction() == null || rootTask.instruction().isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "rootTask.instruction must not be blank");
        }

        // memberAgents 非空（SupervisorDefinition compact constructor 已保证）
        Set<String> memberAgents = definition.memberAgents();

        // 检查重复
        if (memberAgents.size() != memberAgents.stream().distinct().count()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "memberAgents contains duplicate names");
        }

        // 检查每个成员 Agent 是否存在于 AgentRegistry
        for (String agentName : memberAgents) {
            if (!agentRegistry.contains(agentName)) {
                throw new AgentFrameworkException(
                        AgentErrorCode.AGENT_NOT_FOUND,
                        "Member agent not found in AgentRegistry: " + agentName
                );
            }
        }
    }
}
