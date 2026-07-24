package com.ksyun.agent.core.security;

import java.util.Collection;

/**
 * 角色提供者 SPI 接口。
 * <p>
 * 与 AgentProvider、ToolProvider、SupervisorProvider 模式一致。
 * Provider 不直接操作 Spring 容器。
 * 没有 Provider 时应用正常启动。
 */
public interface RoleProvider {

    /**
     * 提供角色定义集合。
     *
     * @return 角色定义列表，不能为 null
     */
    Collection<RoleDefinition> provideRoles();
}
