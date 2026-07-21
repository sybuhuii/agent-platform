package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Agent 注册中心实现。
 * <p>
 * 使用 ConcurrentHashMap，线程安全。重复注册相同名称时明确拒绝。
 */
public class DefaultAgentRegistry implements AgentRegistry {

    private final ConcurrentHashMap<String, AgentDefinition> agents = new ConcurrentHashMap<>();

    @Override
    public void register(AgentDefinition definition) {
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Agent definition and name must not be blank"
            );
        }
        AgentDefinition existing = agents.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Agent already registered with name: " + definition.name()
            );
        }
    }

    @Override
    public Optional<AgentDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agents.get(name));
    }

    @Override
    public AgentDefinition getRequired(String name) {
        return find(name).orElseThrow(() ->
                new AgentFrameworkException(
                        AgentErrorCode.AGENT_NOT_FOUND,
                        "Agent not found: " + name
                )
        );
    }

    @Override
    public Collection<AgentDefinition> list() {
        return Collections.unmodifiableCollection(agents.values());
    }

    @Override
    public boolean contains(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return agents.containsKey(name);
    }
}
