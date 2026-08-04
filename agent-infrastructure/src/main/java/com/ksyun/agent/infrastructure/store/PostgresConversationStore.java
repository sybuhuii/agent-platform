package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationMessageRole;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.conversation.MessageIdGenerator;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.store.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL 会话历史存储实现。
 * <p>
 * 行为与 {@link InMemoryConversationStore} 的公开语义一致。
 * <p>
 * sequence_no 通过 agent_threads.next_sequence 原子分配：
 * UPDATE ... SET next_sequence = next_sequence + 2 RETURNING next_sequence - 2
 * 禁止使用 SELECT MAX(sequence_no)+1。
 * <p>
 * 去重键通过 agent_messages(thread_id, deduplication_key) 唯一索引保证：
 * - 相同去重键+相同内容：先查已存在，命中则幂等返回。
 * - 相同去重键+不同内容：唯一约束冲突，报告冲突。
 * <p>
 * 同一轮的两条消息在一个数据库事务内写入。
 * 不添加 @Component，通过 @Bean 装配。
 */
public class PostgresConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresConversationStore.class);

    private static final int MAX_PAGE_SIZE = 100;

    private static final String INSERT_THREAD_SQL =
            "INSERT INTO agent_threads (thread_id, user_id, title, pinned, archived, agent_name, "
                    + "next_sequence, created_at, last_message_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (thread_id) DO NOTHING";

    private static final String UPSERT_THREAD_CONFLICT_SQL =
            "INSERT INTO agent_threads (thread_id, user_id, title, pinned, archived, agent_name, "
                    + "next_sequence, created_at, last_message_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (thread_id) DO UPDATE SET user_id = agent_threads.user_id "
                    + "RETURNING (xmax = 0) AS inserted";

    private static final String ALLOC_TWO_SEQ_SQL =
            "UPDATE agent_threads SET next_sequence = next_sequence + 2 WHERE thread_id = ? AND user_id = ? "
                    + "RETURNING next_sequence - 2 AS first_seq";

    private static final String ALLOC_ONE_SEQ_SQL =
            "UPDATE agent_threads SET next_sequence = next_sequence + 1 WHERE thread_id = ? AND user_id = ? "
                    + "RETURNING next_sequence - 1 AS seq";

    private static final String INSERT_MESSAGE_SQL =
            "INSERT INTO agent_messages (message_id, thread_id, sequence_no, role, content, deduplication_key, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    private static final String FIND_MESSAGE_BY_DEDUP_SQL =
            "SELECT message_id, thread_id, sequence_no, role, content, deduplication_key, created_at "
                    + "FROM agent_messages WHERE thread_id = ? AND deduplication_key = ?";

    private static final String FIND_THREAD_SQL =
            "SELECT thread_id, user_id, title, pinned, archived, agent_name, created_at, last_message_at, updated_at "
                    + "FROM agent_threads WHERE thread_id = ? AND user_id = ?";

    private static final String LIST_THREADS_SQL =
            "SELECT thread_id, user_id, title, pinned, archived, agent_name, created_at, last_message_at, updated_at "
                    + "FROM agent_threads WHERE user_id = ? AND NOT archived ";

    private static final String LIST_MESSAGES_SQL =
            "SELECT message_id, thread_id, sequence_no, role, content, deduplication_key, created_at "
                    + "FROM agent_messages WHERE thread_id = ? ";

    private static final String UPDATE_THREAD_TIMES_SQL =
            "UPDATE agent_threads SET last_message_at = ?, updated_at = ? WHERE thread_id = ? AND user_id = ?";

    private static final String RENAME_SQL =
            "UPDATE agent_threads SET title = ?, updated_at = ? WHERE thread_id = ? AND user_id = ? AND NOT archived";

    private static final String SET_PINNED_SQL =
            "UPDATE agent_threads SET pinned = ?, updated_at = ? WHERE thread_id = ? AND user_id = ? AND NOT archived";

    private static final String ARCHIVE_SQL =
            "UPDATE agent_threads SET archived = TRUE, pinned = FALSE, updated_at = ? "
                    + "WHERE thread_id = ? AND user_id = ? AND NOT archived";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MessageIdGenerator messageIdGenerator;

    public PostgresConversationStore(JdbcTemplate jdbcTemplate,
                                      PlatformTransactionManager transactionManager,
                                      MessageIdGenerator messageIdGenerator) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.messageIdGenerator = Objects.requireNonNull(messageIdGenerator);
    }

    @Override
    public List<ConversationMessage> createThreadWithFirstRound(
            ConversationThread thread,
            String userMessage,
            String assistantMessage,
            String userDedupKey,
            String assistantDedupKey) {
        Objects.requireNonNull(thread, "thread must not be null");
        validateContent(userMessage);
        validateContent(assistantMessage);

        return transactionTemplate.execute(status -> {
            // 插入 thread（冲突时归属冲突检测）
            Boolean inserted = jdbcTemplate.query(
                    UPSERT_THREAD_CONFLICT_SQL,
                    rs -> rs.next() ? rs.getBoolean("inserted") : null,
                    thread.threadId(), thread.userId(), thread.title(),
                    thread.pinned(), thread.archived(), thread.agentName(),
                    2L,
                    Timestamp.from(thread.createdAt()),
                    Timestamp.from(thread.lastMessageAt()),
                    Timestamp.from(thread.updatedAt()));

            if (inserted != null && !inserted) {
                // thread 已存在，校验归属
                requireOwnedThread(thread.threadId(), thread.userId());
            }

            Instant now = Instant.now();
            return writeRound(thread.threadId(), thread.userId(),
                    userMessage, assistantMessage, userDedupKey, assistantDedupKey, now, now);
        });
    }

    @Override
    public List<ConversationMessage> appendRound(
            String userId,
            String threadId,
            String userMessage,
            String assistantMessage,
            String userDedupKey,
            String assistantDedupKey) {
        validateUserId(userId);
        validateThreadId(threadId);
        validateContent(userMessage);
        validateContent(assistantMessage);

        requireOwnedThread(threadId, userId);

        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            return writeRound(threadId, userId, userMessage, assistantMessage,
                    userDedupKey, assistantDedupKey, now, now);
        });
    }

    @Override
    public ConversationMessage appendAssistantMessage(
            String userId,
            String threadId,
            String assistantMessage,
            String dedupKey) {
        validateUserId(userId);
        validateThreadId(threadId);
        validateContent(assistantMessage);

        requireOwnedThread(threadId, userId);

        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            ConversationMessage msg = writeSingleAssistant(threadId, userId, assistantMessage, dedupKey, now);
            jdbcTemplate.update(UPDATE_THREAD_TIMES_SQL,
                    Timestamp.from(now), Timestamp.from(now), threadId, userId);
            return msg;
        });
    }

    @Override
    public Optional<ConversationThread> findThread(String userId, String threadId) {
        validateUserId(userId);
        validateThreadId(threadId);
        ConversationThread t = jdbcTemplate.query(FIND_THREAD_SQL,
                rs -> rs.next() ? mapThread(rs) : null, threadId, userId);
        return Optional.ofNullable(t);
    }

    @Override
    public List<ConversationThread> listThreads(String userId, ThreadCursor before, int limit) {
        validateUserId(userId);
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, MAX_PAGE_SIZE);

        StringBuilder sql = new StringBuilder(LIST_THREADS_SQL);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (before != null) {
            // 游标分页：用 last_message_at + thread_id 排除已加载项
            sql.append(" AND (last_message_at, thread_id) < (?, ?) ");
            args.add(Timestamp.from(before.lastMessageAt()));
            args.add(before.threadId());
        }
        sql.append(" ORDER BY pinned DESC, last_message_at DESC, thread_id ASC LIMIT ?");
        args.add(safeLimit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapThread(rs), args.toArray());
    }

    @Override
    public List<ConversationMessage> listMessages(
            String userId,
            String threadId,
            Long beforeSequence,
            int limit) {
        validateUserId(userId);
        validateThreadId(threadId);
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, MAX_PAGE_SIZE);
        requireOwnedThread(threadId, userId);

        StringBuilder sql = new StringBuilder(LIST_MESSAGES_SQL);
        List<Object> args = new ArrayList<>();
        args.add(threadId);
        if (beforeSequence != null) {
            sql.append(" AND sequence_no < ? ");
            args.add(beforeSequence);
        }
        sql.append(" ORDER BY sequence_no DESC LIMIT ?");
        args.add(safeLimit);

        List<ConversationMessage> desc = jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> mapMessage(rs), args.toArray());
        // 翻转为升序返回
        List<ConversationMessage> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        return asc;
    }

    @Override
    public Optional<ConversationThread> rename(String userId, String threadId, String title) {
        validateUserId(userId);
        validateThreadId(threadId);
        String safeTitle = title == null ? "" : title.trim();
        Instant now = Instant.now();
        int affected = jdbcTemplate.update(RENAME_SQL,
                safeTitle, Timestamp.from(now), threadId, userId);
        if (affected == 0) {
            return Optional.empty();
        }
        return findThread(userId, threadId);
    }

    @Override
    public Optional<ConversationThread> setPinned(String userId, String threadId, boolean pinned) {
        validateUserId(userId);
        validateThreadId(threadId);
        Instant now = Instant.now();
        int affected = jdbcTemplate.update(SET_PINNED_SQL,
                pinned, Timestamp.from(now), threadId, userId);
        if (affected == 0) {
            return Optional.empty();
        }
        return findThread(userId, threadId);
    }

    @Override
    public Optional<ConversationThread> archive(String userId, String threadId) {
        validateUserId(userId);
        validateThreadId(threadId);
        Instant now = Instant.now();
        int affected = jdbcTemplate.update(ARCHIVE_SQL,
                Timestamp.from(now), threadId, userId);
        if (affected == 0) {
            return Optional.empty();
        }
        return findThread(userId, threadId);
    }

    // ---- 内部 ----

    private List<ConversationMessage> writeRound(
            String threadId,
            String userId,
            String userContent,
            String assistantContent,
            String userDedupKey,
            String assistantDedupKey,
            Instant lastAt,
            Instant updatedAt) {
        // 先检查去重键幂等
        ConversationMessage existingUser = userContent != null
                ? findExistingByDedup(threadId, userDedupKey) : null;
        ConversationMessage existingAssistant = findExistingByDedup(threadId, assistantDedupKey);

        List<ConversationMessage> result = new ArrayList<>(2);

        if (userContent != null) {
            result.add(existingUser != null
                    ? existingUser
                    : writeMessageWithAllocatedSeq(threadId, userId,
                    ConversationMessageRole.USER, userContent, userDedupKey));
        }
        result.add(existingAssistant != null
                ? existingAssistant
                : writeMessageWithAllocatedSeq(threadId, userId,
                ConversationMessageRole.ASSISTANT, assistantContent, assistantDedupKey));

        // 更新 thread 时间（仅当本轮至少写入一条新消息）
        if (existingUser == null || existingAssistant == null) {
            jdbcTemplate.update(UPDATE_THREAD_TIMES_SQL,
                    Timestamp.from(lastAt), Timestamp.from(updatedAt), threadId, userId);
        }
        return result;
    }

    private ConversationMessage writeSingleAssistant(
            String threadId,
            String userId,
            String assistantContent,
            String dedupKey,
            Instant now) {
        ConversationMessage existing = findExistingByDedup(threadId, dedupKey);
        if (existing != null) {
            return existing;
        }
        return writeMessageWithAllocatedSeq(threadId, userId,
                ConversationMessageRole.ASSISTANT, assistantContent, dedupKey);
    }

    private ConversationMessage writeMessageWithAllocatedSeq(
            String threadId,
            String userId,
            ConversationMessageRole role,
            String content,
            String dedupKey) {
        String normalizedDedup = dedupKey == null || dedupKey.isBlank() ? null : dedupKey.trim();
        String normalizedContent = content.trim();
        Instant now = Instant.now();
        String msgId = messageIdGenerator.nextMessageId();

        // 分配单个 sequence
        Long seq = jdbcTemplate.query(ALLOC_ONE_SEQ_SQL,
                rs -> rs.next() ? rs.getLong("seq") : null, threadId, userId);
        if (seq == null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for sequence allocation");
        }

        try {
            jdbcTemplate.update(INSERT_MESSAGE_SQL,
                    msgId, threadId, seq,
                    role.name(), normalizedContent, normalizedDedup, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 去重键冲突：相同键不同内容
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Deduplication key conflict: same key with different content");
        }

        return new ConversationMessage(msgId, threadId, seq, role, normalizedContent, normalizedDedup, now);
    }

    private ConversationMessage findExistingByDedup(String threadId, String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return null;
        }
        String normalized = dedupKey.trim();
        return jdbcTemplate.query(FIND_MESSAGE_BY_DEDUP_SQL,
                rs -> rs.next() ? mapMessage(rs) : null, threadId, normalized);
    }

    private void requireOwnedThread(String threadId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_threads WHERE thread_id = ? AND user_id = ?",
                Integer.class, threadId, userId);
        if (count == null || count == 0) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for user");
        }
    }

    private ConversationThread mapThread(ResultSet rs) throws SQLException {
        return new ConversationThread(
                rs.getString("thread_id"),
                rs.getString("user_id"),
                rs.getString("title"),
                rs.getBoolean("pinned"),
                rs.getBoolean("archived"),
                rs.getString("agent_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_message_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private ConversationMessage mapMessage(ResultSet rs) throws SQLException {
        String dedup = rs.getString("deduplication_key");
        return new ConversationMessage(
                rs.getString("message_id"),
                rs.getString("thread_id"),
                rs.getLong("sequence_no"),
                ConversationMessageRole.valueOf(rs.getString("role")),
                rs.getString("content"),
                dedup,
                rs.getTimestamp("created_at").toInstant());
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "userId must not be blank");
        }
    }

    private void validateThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "message content must not be blank");
        }
    }
}
