package com.ksyun.agent.core.supervisor;

import java.util.Collection;

/**
 * Supervisor 提供者 SPI，业务模块通过实现此接口批量提供 Supervisor 定义。
 * <p>
 * 不得依赖 Spring 容器。
 * 不得直接操作 SupervisorRegistry。
 */
public interface SupervisorProvider {

    /**
     * 提供 Supervisor 定义集合。
     */
    Collection<SupervisorDefinition> provideSupervisors();
}
