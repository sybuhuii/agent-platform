package com.ksyun.agent.core.security;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 角色权限解析器接口。
 * <p>
 * 根据角色名称集合查找 RoleDefinition 并合并权限。
 * 不得访问 ToolRegistry。
 * 不得判断某个具体工具是否可调用。
 * 不得读取当前线程或 Spring Security 上下文。
 * 不得保存请求级状态。
 */
public interface RolePermissionResolver {

    /**
     * 根据角色名称集合解析合并后的权限编码集合。
     *
     * @param roleNames 角色名称集合，不能为空
     * @return 去重后的不可变权限编码集合
     */
    Set<String> resolvePermissions(Set<String> roleNames);
}
