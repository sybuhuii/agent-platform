package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * Session 校验服务，纯 Java 实现。
 * <p>
 * 负责读取、校验和过期判断。
 * 不保存明文密码、不生成 sessionId。
 * 不在日志中记录 sessionId。
 * 不实现续期。
 * 不通过 sessionId 格式推断用户身份。
 * 使用注入的 Clock 判断是否过期，不使用 Instant.now()。
 */
public class SessionValidationService {

    private static final Logger log = LoggerFactory.getLogger(SessionValidationService.class);

    private final SessionStore sessionStore;
    private final Clock clock;

    public SessionValidationService(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
        this.clock = Clock.systemUTC();
    }

    /**
     * 校验并返回当前有效 Session。
     * <p>
     * sessionId 为空 → SESSION_INVALID。
     * 查不到 → SESSION_NOT_FOUND。
     * 过期 → SESSION_EXPIRED（并删除）。
     * 不通过 sessionId 格式推断用户身份。
     *
     * @param sessionId 会话 ID
     * @return 有效 UserSession
     */
    public UserSession requireValidSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.SESSION_INVALID, "sessionId must not be blank");
        }

        UserSession session = sessionStore.findBySessionId(sessionId)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.SESSION_NOT_FOUND,
                        "Session not found"
                ));

        Instant now = clock.instant();
        if (session.isExpired(now)) {
            sessionStore.delete(sessionId);
            throw new AgentFrameworkException(AgentErrorCode.SESSION_EXPIRED, "Session has expired");
        }

        return session;
    }
}
