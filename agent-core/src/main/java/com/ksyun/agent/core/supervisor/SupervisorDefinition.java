package com.ksyun.agent.core.supervisor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * Supervisor 定义，描述一个多 Agent 调度者。
 * <p>
 * 使用不可变 record，name 不能为空，memberAgents 至少包含一个 Agent 名称。
 * 不直接保存 AgentDefinition 对象，使用 Agent 名称降低配置耦合。
 * 不包含 SpringAI、LangGraph4j 或 Spring 类型。
 *
 * @param name          Supervisor 名称，不能为空
 * @param description   描述
 * @param systemPrompt  系统提示词
 * @param memberAgents  成员 Agent 名称集合，不可变，不能为空，至少包含一个名称
 * @param maxIterations 最大迭代次数，必须大于 0
 */
public record SupervisorDefinition(
        String name,
        String description,
        String systemPrompt,
        Set<String> memberAgents,
        int maxIterations
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SupervisorDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SupervisorDefinition name must not be blank");
        }
        if (memberAgents == null || memberAgents.isEmpty()) {
            throw new IllegalArgumentException("SupervisorDefinition memberAgents must not be empty");
        }
        for (String agentName : memberAgents) {
            if (agentName == null || agentName.isBlank()) {
                throw new IllegalArgumentException("SupervisorDefinition memberAgents must not contain blank names");
            }
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("SupervisorDefinition maxIterations must be greater than 0");
        }
        memberAgents = Collections.unmodifiableSet(memberAgents);
    }
}
