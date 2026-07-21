package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * 接收多个 AgentProvider，将其返回的全部 AgentDefinition 注册到 AgentRegistry。
 */
public class AgentProviderRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AgentProviderRegistrar.class);

    private final AgentRegistry registry;
    private final List<AgentProvider> providers;

    public AgentProviderRegistrar(AgentRegistry registry, List<AgentProvider> providers) {
        this.registry = registry;
        this.providers = providers != null ? providers : List.of();
    }

    /**
     * 将所有 Provider 提供的 Agent 定义注册到 Registry。
     */
    public void registerAll() {
        for (AgentProvider provider : providers) {
            Collection<AgentDefinition> definitions = safeProvide(provider);
            for (AgentDefinition definition : definitions) {
                try {
                    registry.register(definition);
                    log.info("Registered agent: {}", definition.name());
                } catch (Exception e) {
                    log.error("Failed to register agent: {}", definition.name(), e);
                    throw e;
                }
            }
        }
    }

    private Collection<AgentDefinition> safeProvide(AgentProvider provider) {
        try {
            Collection<AgentDefinition> result = provider.provideAgents();
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AgentProvider " + provider.getClass().getName() + " failed to provide agents", e
            );
        }
    }
}
