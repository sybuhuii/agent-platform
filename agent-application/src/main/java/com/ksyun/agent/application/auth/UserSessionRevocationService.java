package com.ksyun.agent.application.auth;

import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * 用户 Session 批量撤销服务。
 * <p>
 * 当用户角色、状态或密码发生变化，或角色权限发生变化时，
 * 撤销受影响用户的所有旧 Session，强制重新登录以获取新的权限快照。
 * <p>
 * 纯 Java 实现，不依赖 Spring。保持无状态和线程安全。
 * 不得在此服务中修改用户或角色。
 */
public class UserSessionRevocationService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionRevocationService.class);

    private final SessionStore sessionStore;

    public UserSessionRevocationService(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 撤销指定用户的所有 Session。
     *
     * @param userId 用户 ID
     * @return 实际撤销数量
     */
    public int revokeAllForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }

        Collection<UserSession> sessions = sessionStore.findByUserId(userId);
        int count = 0;
        for (UserSession session : sessions) {
            sessionStore.delete(session.sessionId());
            count++;
        }

        if (count > 0) {
            log.info("Sessions revoked: userId={}, count={}", userId, count);
        }
        return count;
    }

    /**
     * 批量撤销多个用户的所有 Session。
     *
     * @param userIds 用户 ID 集合
     * @return 实际撤销总数量
     */
    public int revokeAllForUsers(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }

        int totalCount = 0;
        for (String userId : userIds) {
            totalCount += revokeAllForUser(userId);
        }
        return totalCount;
    }
}
