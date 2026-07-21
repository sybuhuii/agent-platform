package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.agent.AgentDefinition;

import java.util.Collection;
import java.util.Optional;

/**
 * Agent 注册中心接口。
 */
public interface AgentRegistry {

    void register(AgentDefinition definition);

    Optional<AgentDefinition> find(String name);

    AgentDefinition getRequired(String name);

    Collection<AgentDefinition> list();

    boolean contains(String name);
}
