package com.ksyun.agent.core.store;

import com.ksyun.agent.core.security.UserAccount;

import java.util.Collection;
import java.util.Optional;

/**
 * 用户存储接口。
 * <p>
 * 位于 agent-core，不依赖 ConcurrentHashMap、Spring、数据库或 Redis。
 * 查询不存在返回 Optional.empty。写入 null 明确拒绝。
 */
public interface UserStore {

    void save(UserAccount user);

    void update(UserAccount user);

    Optional<UserAccount> findById(String userId);

    Optional<UserAccount> findByUsername(String username);

    Collection<UserAccount> list();

    boolean existsByUsername(String username);
}
