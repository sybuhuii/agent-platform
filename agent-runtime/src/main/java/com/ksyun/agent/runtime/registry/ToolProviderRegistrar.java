package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * 接收多个 ToolProvider，将其返回的全部 AgentTool 注册到 ToolRegistry。
 */
public class ToolProviderRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ToolProviderRegistrar.class);

    private final ToolRegistry registry;
    private final List<ToolProvider> providers;

    public ToolProviderRegistrar(ToolRegistry registry, List<ToolProvider> providers) {
        this.registry = registry;
        this.providers = providers != null ? providers : List.of();
    }

    /**
     * 将所有 Provider 提供的工具注册到 Registry。
     */
    public void registerAll() {
        for (ToolProvider provider : providers) {
            Collection<AgentTool> tools = safeProvide(provider);
            for (AgentTool tool : tools) {
                try {
                    registry.register(tool);
                    log.info("Registered tool: {}", tool.definition().name());
                } catch (Exception e) {
                    log.error("Failed to register tool: {}", tool.definition().name(), e);
                    throw e;
                }
            }
        }
    }

    private Collection<AgentTool> safeProvide(ToolProvider provider) {
        try {
            Collection<AgentTool> result = provider.provideTools();
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "ToolProvider " + provider.getClass().getName() + " failed to provide tools", e
            );
        }
    }
}
