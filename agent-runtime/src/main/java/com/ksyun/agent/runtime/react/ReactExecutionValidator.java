package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;

/**
 * ReAct 运行请求校验器，纯 Java 实现。
 * <p>
 * 不调用模型或工具，不修改输入对象。
 */
public class ReactExecutionValidator {

    /**
     * 校验 ReAct 执行请求参数。
     *
     * @param definition Agent 定义
     * @param task       Agent 任务
     * @param context    运行上下文
     * @throws AgentFrameworkException 校验失败时抛出 INVALID_ARGUMENT
     */
    public void validate(AgentDefinition definition, AgentTask task, RunContext context) {
        if (definition == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "AgentDefinition must not be null"
            );
        }
        if (task == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "AgentTask must not be null"
            );
        }
        if (context == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "RunContext must not be null"
            );
        }

        // AgentTask.agentName 应与 AgentDefinition.name 一致
        // 若 agentName 为空/null，视为兼容：不强制要求
        if (task.agentName() != null && !task.agentName().isBlank()) {
            if (!task.agentName().equals(definition.name())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "AgentTask.agentName ('" + task.agentName()
                                + "') does not match AgentDefinition.name ('" + definition.name() + "')"
                );
            }
        }

        // maxIterations 已在 AgentDefinition 紧凑构造器中校验（>0），此处防御性检查
        if (definition.maxIterations() <= 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "AgentDefinition.maxIterations must be greater than 0"
            );
        }

        // task.instruction 非空
        if (task.instruction() == null || task.instruction().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "AgentTask.instruction must not be blank"
            );
        }

        // allowedTools 不为 null（AgentDefinition 紧凑构造器已保证，此处防御性检查）
        if (definition.allowedTools() == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "AgentDefinition.allowedTools must not be null"
            );
        }
    }
}
