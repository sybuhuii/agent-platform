package com.ksyun.agent.runtime.registry;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.tool.AgentTool;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Tool 注册中心实现。
 * <p>
 * 使用 ConcurrentHashMap，线程安全。根据 tool.definition().name() 注册，重复注册明确拒绝。
 */
public class DefaultToolRegistry implements ToolRegistry {

    private final ConcurrentHashMap<String, AgentTool> tools = new ConcurrentHashMap<>();

    @Override
    public void register(AgentTool tool) {
        if (tool == null || tool.definition() == null
                || tool.definition().name() == null || tool.definition().name().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Tool and tool definition name must not be blank"
            );
        }
        String name = tool.definition().name();
        AgentTool existing = tools.putIfAbsent(name, tool);
        if (existing != null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Tool already registered with name: " + name
            );
        }
    }

    @Override
    public Optional<AgentTool> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public AgentTool getRequired(String name) {
        return find(name).orElseThrow(() ->
                new AgentFrameworkException(
                        AgentErrorCode.TOOL_NOT_FOUND,
                        "Tool not found: " + name
                )
        );
    }

    @Override
    public Collection<AgentTool> list() {
        return Collections.unmodifiableCollection(tools.values());
    }

    @Override
    public boolean contains(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return tools.containsKey(name);
    }
}
