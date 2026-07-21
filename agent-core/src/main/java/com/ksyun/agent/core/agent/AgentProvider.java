package com.ksyun.agent.core.agent;

import java.util.Collection;

/**
 * Agent 提供者 SPI，业务模块通过实现此接口批量提供 Agent 定义。
 */
public interface AgentProvider {

    /**
     * 提供 Agent 定义集合。
     */
    Collection<AgentDefinition> provideAgents();
}
