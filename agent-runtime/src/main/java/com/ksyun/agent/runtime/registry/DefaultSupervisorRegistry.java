package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Supervisor 注册中心实现。
 * <p>
 * 使用 ConcurrentHashMap，线程安全。重复注册相同名称时明确拒绝。
 */
public class DefaultSupervisorRegistry implements SupervisorRegistry {

    private final ConcurrentHashMap<String, SupervisorDefinition> supervisors = new ConcurrentHashMap<>();

    @Override
    public void register(SupervisorDefinition definition) {
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor definition and name must not be blank"
            );
        }
        SupervisorDefinition existing = supervisors.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor already registered with name: " + definition.name()
            );
        }
    }

    @Override
    public Optional<SupervisorDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(supervisors.get(name));
    }

    @Override
    public SupervisorDefinition getRequired(String name) {
        return find(name).orElseThrow(() ->
                new AgentFrameworkException(
                        AgentErrorCode.SUPERVISOR_NOT_FOUND,
                        "Supervisor not found: " + name
                )
        );
    }

    @Override
    public Collection<SupervisorDefinition> list() {
        return Collections.unmodifiableCollection(supervisors.values());
    }

    @Override
    public boolean contains(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return supervisors.containsKey(name);
    }
}
