package com.ksyun.agent.core.tool;

import java.util.Collection;

/**
 * 工具提供者 SPI，业务模块通过实现此接口批量提供工具。
 */
public interface ToolProvider {

    /**
     * 提供工具集合。
     */
    Collection<AgentTool> provideTools();
}
