package com.ksyun.agent.application.framework;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.core.tool.ToolDefinition;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.registry.ToolRegistry;

import java.util.Collection;

/**
 * 框架查询服务，提供已注册 Agent、Tool 和 Supervisor 的查询能力。
 * <p>
 * 只依赖 runtime/core 的接口，不直接访问 Spring 容器，不实现注册逻辑。
 */
public class FrameworkQueryService {

    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final SupervisorRegistry supervisorRegistry;

    public FrameworkQueryService(AgentRegistry agentRegistry,
                                  ToolRegistry toolRegistry,
                                  SupervisorRegistry supervisorRegistry) {
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.supervisorRegistry = supervisorRegistry;
    }

    /**
     * 查询所有已注册的 Agent 定义。
     */
    public Collection<AgentDefinition> listAgents() {
        return agentRegistry.list();
    }

    /**
     * 查询所有已注册的 Tool 定义。
     */
    public Collection<ToolDefinition> listTools() {
        return toolRegistry.list().stream()
                .map(tool -> tool.definition())
                .toList();
    }

    /**
     * 查询所有已注册的 Supervisor 定义。
     */
    public Collection<SupervisorDefinition> listSupervisors() {
        return supervisorRegistry.list();
    }
}
