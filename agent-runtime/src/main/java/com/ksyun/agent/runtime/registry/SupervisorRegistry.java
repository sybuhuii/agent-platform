package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.supervisor.SupervisorDefinition;

import java.util.Collection;
import java.util.Optional;

/**
 * Supervisor 注册中心接口。
 */
public interface SupervisorRegistry {

    void register(SupervisorDefinition definition);

    Optional<SupervisorDefinition> find(String name);

    SupervisorDefinition getRequired(String name);

    Collection<SupervisorDefinition> list();

    boolean contains(String name);
}
