package com.ksyun.agent.core.store;

import com.ksyun.agent.core.security.UserSession;

import java.util.Collection;
import java.util.Optional;

/**
 * 会话存储接口。
 * <p>
 * 位于 agent-core，不依赖 ConcurrentHashMap、Spring、数据库或 Redis。
 * 查询不存在返回 Optional.empty。写入 null 明确拒绝。
 * 不得在 Store 中生成 sessionId。不得在 Store 中自动续期。
 */
public interface SessionStore {

    void save(UserSession session);

    Optional<UserSession> findBySessionId(String sessionId);

    void delete(String sessionId);

    /**
     * 按用户 ID 查询会话，严格过滤，不同 userId 之间不得串读。
     *
     * @param userId 用户 ID
     * @return 该用户的会话不可变快照
     */
    Collection<UserSession> findByUserId(String userId);
}
