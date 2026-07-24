package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.SessionStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 线程安全的内存 Session 存储。
 * <p>
 * 使用 ConcurrentHashMap，sessionId 为主键。
 * findByUserId 严格按 userId 过滤，不同用户之间不会串读。
 * <p>
 * 过期清理：提供 {@link #removeExpiredSessions(Instant)} 方法，
 * 供上层 SessionValidationService 或定时任务调用，
 * 不自行启动后台线程。
 */
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(UserSession session) {
        if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Session and sessionId must not be null or blank"
            );
        }
        UserSession existing = sessions.putIfAbsent(session.sessionId(), session);
        if (existing != null && !existing.equals(session)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT,
                    "Session already exists with different content: " + session.sessionId()
            );
        }
    }

    @Override
    public Optional<UserSession> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public Collection<UserSession> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return sessions.values().stream()
                .filter(session -> userId.equals(session.userId()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        Collections::unmodifiableList
                ));
    }

    /**
     * 移除所有已过期的 Session。
     * <p>
     * 由上层 SessionValidationService 或定时任务调用，
     * InMemorySessionStore 不自行启动后台线程。
     *
     * @param now 当前时间，用于判断过期
     * @return 被移除的 Session 数量
     */
    public int removeExpiredSessions(Instant now) {
        List<String> expiredIds = new ArrayList<>();
        for (UserSession session : sessions.values()) {
            if (session.isExpired(now)) {
                expiredIds.add(session.sessionId());
            }
        }
        for (String id : expiredIds) {
            sessions.remove(id);
        }
        return expiredIds.size();
    }
}
