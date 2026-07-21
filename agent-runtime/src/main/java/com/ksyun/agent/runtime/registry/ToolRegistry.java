package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.tool.AgentTool;

import java.util.Collection;
import java.util.Optional;

/**
 * Tool 注册中心接口。
 */
public interface ToolRegistry {

    void register(AgentTool tool);

    Optional<AgentTool> find(String name);

    AgentTool getRequired(String name);

    Collection<AgentTool> list();

    boolean contains(String name);
}
