package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.core.supervisor.SupervisorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * 接收多个 SupervisorProvider，将其返回的全部 SupervisorDefinition 注册到 SupervisorRegistry。
 */
public class SupervisorProviderRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SupervisorProviderRegistrar.class);

    private final SupervisorRegistry registry;
    private final List<SupervisorProvider> providers;

    public SupervisorProviderRegistrar(SupervisorRegistry registry, List<SupervisorProvider> providers) {
        this.registry = registry;
        this.providers = providers != null ? providers : List.of();
    }

    /**
     * 将所有 Provider 提供的 Supervisor 定义注册到 Registry。
     */
    public void registerAll() {
        for (SupervisorProvider provider : providers) {
            Collection<SupervisorDefinition> definitions = safeProvide(provider);
            for (SupervisorDefinition definition : definitions) {
                try {
                    registry.register(definition);
                    log.info("Registered supervisor: {}", definition.name());
                } catch (Exception e) {
                    log.error("Failed to register supervisor: {}", definition.name(), e);
                    throw e;
                }
            }
        }
    }

    private Collection<SupervisorDefinition> safeProvide(SupervisorProvider provider) {
        try {
            Collection<SupervisorDefinition> result = provider.provideSupervisors();
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "SupervisorProvider " + provider.getClass().getName() + " failed to provide supervisors", e
            );
        }
    }
}
