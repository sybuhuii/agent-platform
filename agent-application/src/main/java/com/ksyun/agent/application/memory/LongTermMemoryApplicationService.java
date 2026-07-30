package com.ksyun.agent.application.memory;

import com.ksyun.agent.core.memory.MemoryEntry;
import com.ksyun.agent.core.memory.MemoryIdGenerator;
import com.ksyun.agent.core.memory.MemoryStoreKey;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.MemoryStore;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * 长期记忆应用服务，纯 Java 实现。
 * <p>
 * 依赖：MemoryStore、MemoryIdGenerator、Clock、MemoryEntryValidator。
 * userId 只取 operator.userId，不得从 command 读取 userId。
 * 不得访问 CheckpointStore、调用模型、自动提取用户偏好、注入 Agent Prompt。
 * 保持无状态和线程安全。不得使用 ThreadLocal。
 */
public class LongTermMemoryApplicationService {

    private final MemoryStore memoryStore;
    private final MemoryIdGenerator memoryIdGenerator;
    private final Clock clock;
    private final MemoryEntryValidator validator;

    public LongTermMemoryApplicationService(MemoryStore memoryStore,
                                              MemoryIdGenerator memoryIdGenerator,
                                              Clock clock,
                                              MemoryEntryValidator validator) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "MemoryStore must not be null");
        this.memoryIdGenerator = Objects.requireNonNull(memoryIdGenerator, "MemoryIdGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.validator = Objects.requireNonNull(validator, "MemoryEntryValidator must not be null");
    }

    /**
     * 写入或更新长期记忆。
     * <p>
     * operator 不能为空；userId 只取 operator.userId。
     * 统一验证 namespace、key、value 和 metadata。
     * 首次写入生成 memoryId，version=0，createdAt=updatedAt=Clock.instant()。
     * 更新时由 Store 执行原子 version 增加，复用原 memoryId 和 createdAt。
     *
     * @param operator 已认证用户会话
     * @param command  写入命令（不含 userId）
     * @return 记忆视图
     */
    public MemoryView put(UserSession operator, MemoryWriteCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        return putForAuthenticatedUser(operator.userId(), command);
    }

    /**
     * 写入或更新长期记忆（使用已认证用户 ID）。
     * <p>
     * authenticatedUserId 必须来自框架内部 RunContext，不得来自客户端请求体。
     * 现有 put(UserSession, ...) 委托该方法。
     * 不得复制两套 upsert 逻辑。
     * 不得允许 Controller 直接从请求体传 userId 调用该方法。
     * 不得跳过 MemoryEntryValidator。
     * 不得伪造 UserSession。
     * 不得构造假的 Session ID。
     * 不得降低敏感信息校验。
     * 不得访问 CheckpointStore。
     * 保持无状态和线程安全。
     *
     * @param authenticatedUserId 已认证用户 ID，不得为空
     * @param command             写入命令（不含 userId）
     * @return 记忆视图
     */
    public MemoryView putForAuthenticatedUser(String authenticatedUserId, MemoryWriteCommand command) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (authenticatedUserId.isBlank()) {
            throw new IllegalArgumentException("authenticatedUserId must not be blank");
        }

        String userId = authenticatedUserId.trim();
        Instant now = clock.instant();

        // 校验输入
        validator.validate(command);

        // 构造 MemoryEntry
        MemoryEntry entry = new MemoryEntry(
                memoryIdGenerator.generate(),
                userId,
                command.namespace().trim(),
                command.key().trim(),
                command.value().trim(),
                command.category(),
                command.metadata(),
                0,
                now,
                now
        );

        try {
            MemoryEntry stored = memoryStore.put(entry);
            return MemoryView.from(stored);
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to save long-term memory",
                    e);
        }
    }

    /**
     * 查询长期记忆。
     *
     * @param operator  已认证用户会话
     * @param namespace 命名空间
     * @param key       记忆键
     * @return 记忆视图，不存在返回 Optional.empty()
     */
    public Optional<MemoryView> get(
            UserSession operator,
            String namespace,
            String key
    ) {
        Objects.requireNonNull(
                operator,
                "operator must not be null");

        validator.validateNamespace(namespace);
        validator.validateKey(key);

        try {
            return memoryStore.get(
                            operator.userId(),
                            namespace.trim(),
                            key.trim())
                    .map(MemoryView::from);
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to read long-term memory",
                    e);
        }
    }

    /**
     * 列出指定用户和命名空间下的所有记忆。
     *
     * @param operator  已认证用户会话
     * @param namespace 命名空间
     * @return 不可变记忆视图集合
     */
    public Collection<MemoryView> list(
            UserSession operator,
            String namespace
    ) {
        Objects.requireNonNull(
                operator,
                "operator must not be null");

        validator.validateNamespace(namespace);

        try {
            return memoryStore.list(
                            operator.userId(),
                            namespace.trim())
                    .stream()
                    .map(MemoryView::from)
                    .toList();
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to list long-term memory",
                    e);
        }
    }

    /**
     * 删除指定记忆。
     *
     * @param operator  已认证用户会话
     * @param namespace 命名空间
     * @param key       记忆键
     * @return true 表示已删除，false 表示不存在
     */
    public boolean delete(
            UserSession operator,
            String namespace,
            String key
    ) {
        Objects.requireNonNull(
                operator,
                "operator must not be null");

        validator.validateNamespace(namespace);
        validator.validateKey(key);

        try {
            return memoryStore.delete(
                    operator.userId(),
                    namespace.trim(),
                    key.trim());
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to delete long-term memory",
                    e);
        }
    }
}
