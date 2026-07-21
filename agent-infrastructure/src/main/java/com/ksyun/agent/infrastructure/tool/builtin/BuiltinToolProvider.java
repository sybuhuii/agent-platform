package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolProvider;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 内置工具提供者，通过构造器接收内置工具集合。
 * <p>
 * 不直接访问 ApplicationContext，不调用 ToolRegistry.register。
 * 注册行为由第一阶段已有 ToolProviderRegistrar 负责。
 */
public class BuiltinToolProvider implements ToolProvider {

    private final List<AgentTool> tools;

    public BuiltinToolProvider(List<AgentTool> tools) {
        this.tools = Collections.unmodifiableList(tools);
    }

    @Override
    public Collection<AgentTool> provideTools() {
        return tools;
    }
}
