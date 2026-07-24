package com.ksyun.agent.core.store;

import com.ksyun.agent.core.security.RoleDefinition;

import java.util.Collection;
import java.util.Optional;

/**
 * 角色存储接口。
 * <p>
 * 位于 agent-core，不依赖 ConcurrentHashMap、Spring、数据库或 Redis。
 * 查询不存在返回 Optional.empty。写入 null 明确拒绝。
 */
public interface RoleStore {

    void save(RoleDefinition role);

    void update(RoleDefinition role);

    Optional<RoleDefinition> find(String roleName);

    RoleDefinition getRequired(String roleName);

    Collection<RoleDefinition> list();
}
