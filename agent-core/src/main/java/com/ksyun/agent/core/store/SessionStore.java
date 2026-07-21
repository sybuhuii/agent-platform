package com.ksyun.agent.core.store;

import com.ksyun.agent.core.security.UserSession;

import java.util.Optional;

/**
 * 会话存储接口。
 */
public interface SessionStore {

    void save(UserSession session);

    Optional<UserSession> findBySessionId(String sessionId);

    void delete(String sessionId);
}
