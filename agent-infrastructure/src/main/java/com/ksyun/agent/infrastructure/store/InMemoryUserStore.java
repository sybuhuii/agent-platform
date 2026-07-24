package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserAccount;
import com.ksyun.agent.core.store.UserStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存用户存储实现。
 * <p>
 * 使用 ConcurrentHashMap，同时支持按 userId 和 username 查询。
 * 双索引一致性策略：写操作（save）使用 userId 作为锁对象保证双索引原子性，
 * 读操作无锁。不得添加 @Component。不得内置用户。
 */
public class InMemoryUserStore implements UserStore {

    private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> usernameToId = new ConcurrentHashMap<>();

    @Override
    public void save(UserAccount user) {
        Objects.requireNonNull(user, "UserAccount must not be null");

        // 使用 userId 作为锁对象保证双索引原子性
        synchronized (user.userId().intern()) {
            // 检查用户名唯一性：用户名已被其他 userId 占用
            String existingIdForUsername = usernameToId.get(user.username());
            if (existingIdForUsername != null && !existingIdForUsername.equals(user.userId())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "Username already bound to a different userId: " + user.username()
                );
            }

            // 检查 userId 是否已存在且绑定了不同用户名
            UserAccount existing = byId.get(user.userId());
            if (existing != null && !existing.username().equals(user.username())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "UserId already bound to a different username: " + user.userId()
                );
            }

            byId.put(user.userId(), user);
            usernameToId.put(user.username(), user.userId());
        }
    }

    @Override
    public void update(UserAccount user) {
        Objects.requireNonNull(user, "UserAccount must not be null");

        synchronized (user.userId().intern()) {
            UserAccount existing = byId.get(user.userId());
            if (existing == null) {
                throw new AgentFrameworkException(
                        AgentErrorCode.USER_NOT_FOUND,
                        "User not found for update: " + user.userId()
                );
            }

            // userId 和 username 创建后不可修改
            if (!existing.username().equals(user.username())) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INVALID_ARGUMENT,
                        "Username cannot be changed after creation"
                );
            }

            byId.put(user.userId(), user);
            // username 未变，无需更新 usernameToId 索引
        }
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(userId));
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String userId = usernameToId.get(UserAccount.normalizeUsername(username));
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(userId));
    }

    @Override
    public Collection<UserAccount> list() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    @Override
    public boolean existsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return usernameToId.containsKey(UserAccount.normalizeUsername(username));
    }
}
