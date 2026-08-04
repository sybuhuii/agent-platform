package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationMessageRole;
import com.ksyun.agent.core.conversation.ConversationParticipantType;
import com.ksyun.agent.core.conversation.ConversationReply;
import com.ksyun.agent.core.conversation.ConversationRound;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.conversation.MessageIdGenerator;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.store.ConversationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL implementation of user-visible conversation history. */
public class PostgresConversationStore implements ConversationStore {

    private static final int MAX_PAGE_SIZE = 101;
    private static final String THREAD_COLUMNS =
            "thread_id, user_id, title, pinned, archived, participant_type, participant_name, "
                    + "created_at, last_message_at, updated_at";
    private static final String MESSAGE_COLUMNS =
            "message_id, thread_id, sequence_no, role, content, run_id, success, error_code, "
                    + "run_status, deduplication_key, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MessageIdGenerator messageIdGenerator;
    private final Clock clock;

    public PostgresConversationStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            MessageIdGenerator messageIdGenerator,
            Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.messageIdGenerator = Objects.requireNonNull(messageIdGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public List<ConversationMessage> createThreadWithFirstRound(
            ConversationThread thread, ConversationRound round) {
        Objects.requireNonNull(thread, "thread must not be null");
        Objects.requireNonNull(round, "round must not be null");
        return transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO agent_threads (thread_id, user_id, title, pinned, archived, "
                            + "participant_type, participant_name, next_sequence, created_at, last_message_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?) ON CONFLICT (thread_id) DO NOTHING",
                    thread.threadId(), thread.userId(), thread.title(), thread.pinned(), thread.archived(),
                    thread.participantType().name(), thread.participantName(),
                    Timestamp.from(thread.createdAt()), Timestamp.from(thread.lastMessageAt()),
                    Timestamp.from(thread.updatedAt()));
            requireOwnedParticipantForUpdate(thread.threadId(), thread.userId(),
                    thread.participantType(), thread.participantName());
            return writeRound(thread.threadId(), thread.userId(), round);
        });
    }

    @Override
    public List<ConversationMessage> appendRound(
            String userId, String threadId,
            ConversationParticipantType expectedParticipantType,
            String expectedParticipantName,
            ConversationRound round) {
        String safeUserId = normalize(userId, "userId");
        String safeThreadId = normalize(threadId, "threadId");
        Objects.requireNonNull(round, "round must not be null");
        return transactionTemplate.execute(status -> {
            requireOwnedParticipantForUpdate(safeThreadId, safeUserId,
                    expectedParticipantType, expectedParticipantName);
            return writeRound(safeThreadId, safeUserId, round);
        });
    }

    @Override
    public ConversationMessage appendAssistantMessage(
            String userId, String threadId,
            ConversationParticipantType expectedParticipantType,
            String expectedParticipantName,
            ConversationReply reply) {
        String safeUserId = normalize(userId, "userId");
        String safeThreadId = normalize(threadId, "threadId");
        Objects.requireNonNull(reply, "reply must not be null");
        return transactionTemplate.execute(status -> {
            requireOwnedParticipantForUpdate(safeThreadId, safeUserId,
                    expectedParticipantType, expectedParticipantName);
            ConversationMessage message = writeAssistant(safeThreadId, safeUserId, reply);
            touchThread(safeThreadId, safeUserId, message.createdAt());
            return message;
        });
    }

    @Override
    public Optional<ConversationThread> findThread(String userId, String threadId) {
        ConversationThread thread = jdbcTemplate.query(
                "SELECT " + THREAD_COLUMNS + " FROM agent_threads WHERE thread_id = ? AND user_id = ?",
                rs -> rs.next() ? mapThread(rs) : null,
                normalize(threadId, "threadId"), normalize(userId, "userId"));
        return Optional.ofNullable(thread);
    }

    @Override
    public List<ConversationThread> listThreads(String userId, ThreadCursor before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(THREAD_COLUMNS)
                .append(" FROM agent_threads WHERE user_id = ? AND NOT archived");
        List<Object> args = new ArrayList<>();
        args.add(normalize(userId, "userId"));
        if (before != null) {
            sql.append(" AND ((pinned = FALSE AND ? = TRUE) OR (pinned = ? AND ")
                    .append("(last_message_at < ? OR (last_message_at = ? AND thread_id > ?))))");
            args.add(before.pinned());
            args.add(before.pinned());
            args.add(Timestamp.from(before.lastMessageAt()));
            args.add(Timestamp.from(before.lastMessageAt()));
            args.add(before.threadId());
        }
        sql.append(" ORDER BY pinned DESC, last_message_at DESC, thread_id ASC LIMIT ?");
        args.add(Math.max(0, Math.min(limit, MAX_PAGE_SIZE)));
        return List.copyOf(jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapThread(rs), args.toArray()));
    }

    @Override
    public List<ConversationMessage> listMessages(
            String userId, String threadId, Long beforeSequence, int limit) {
        String safeThreadId = normalize(threadId, "threadId");
        requireOwned(safeThreadId, normalize(userId, "userId"));
        StringBuilder sql = new StringBuilder("SELECT ").append(MESSAGE_COLUMNS)
                .append(" FROM agent_messages WHERE thread_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(safeThreadId);
        if (beforeSequence != null) {
            sql.append(" AND sequence_no < ?");
            args.add(beforeSequence);
        }
        sql.append(" ORDER BY sequence_no DESC LIMIT ?");
        args.add(Math.max(0, Math.min(limit, MAX_PAGE_SIZE)));
        List<ConversationMessage> messages = new ArrayList<>(
                jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapMessage(rs), args.toArray()));
        Collections.reverse(messages);
        return List.copyOf(messages);
    }

    @Override
    public Optional<ConversationThread> rename(String userId, String threadId, String title) {
        return updateThread("title = ?", normalize(title, "title"), userId, threadId);
    }

    @Override
    public Optional<ConversationThread> setPinned(String userId, String threadId, boolean pinned) {
        return updateThread("pinned = ?", pinned, userId, threadId);
    }

    @Override
    public Optional<ConversationThread> archive(String userId, String threadId) {
        String safeUserId = normalize(userId, "userId");
        String safeThreadId = normalize(threadId, "threadId");
        int affected = jdbcTemplate.update(
                "UPDATE agent_threads SET archived = TRUE, pinned = FALSE, updated_at = ? "
                        + "WHERE thread_id = ? AND user_id = ? AND NOT archived",
                Timestamp.from(clock.instant()), safeThreadId, safeUserId);
        return affected == 0 ? Optional.empty() : findThread(safeUserId, safeThreadId);
    }

    private Optional<ConversationThread> updateThread(
            String assignment, Object value, String userId, String threadId) {
        String safeUserId = normalize(userId, "userId");
        String safeThreadId = normalize(threadId, "threadId");
        int affected = jdbcTemplate.update(
                "UPDATE agent_threads SET " + assignment + ", updated_at = ? "
                        + "WHERE thread_id = ? AND user_id = ? AND NOT archived",
                value, Timestamp.from(clock.instant()), safeThreadId, safeUserId);
        return affected == 0 ? Optional.empty() : findThread(safeUserId, safeThreadId);
    }

    private List<ConversationMessage> writeRound(
            String threadId, String userId, ConversationRound round) {
        List<ConversationMessage> result = new ArrayList<>(2);
        result.add(writeUser(threadId, userId, round));
        round.reply().ifPresent(reply -> result.add(writeAssistant(threadId, userId, reply)));
        touchThread(threadId, userId, result.get(result.size() - 1).createdAt());
        return List.copyOf(result);
    }

    private ConversationMessage writeUser(String threadId, String userId, ConversationRound round) {
        ConversationMessage candidate = new ConversationMessage(
                messageIdGenerator.nextMessageId(), threadId, 0,
                ConversationMessageRole.USER, round.userContent(), round.runId(),
                null, null, null, round.userDeduplicationKey(), clock.instant());
        return writeMessage(threadId, userId, candidate);
    }

    private ConversationMessage writeAssistant(String threadId, String userId, ConversationReply reply) {
        ConversationMessage candidate = new ConversationMessage(
                messageIdGenerator.nextMessageId(), threadId, 0,
                ConversationMessageRole.ASSISTANT, reply.content(), reply.runId(),
                reply.success(), reply.errorCode(), reply.runStatus(),
                reply.deduplicationKey(), clock.instant());
        return writeMessage(threadId, userId, candidate);
    }

    private ConversationMessage writeMessage(
            String threadId, String userId, ConversationMessage candidateWithoutSequence) {
        ConversationMessage existing = findExistingByDedup(threadId, candidateWithoutSequence.deduplicationKey());
        if (existing != null) {
            if (samePayload(existing, candidateWithoutSequence)) {
                return existing;
            }
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Deduplication key conflict: payload differs");
        }
        Long sequence = jdbcTemplate.query(
                "UPDATE agent_threads SET next_sequence = next_sequence + 1 "
                        + "WHERE thread_id = ? AND user_id = ? RETURNING next_sequence - 1",
                rs -> rs.next() ? rs.getLong(1) : null, threadId, userId);
        if (sequence == null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for sequence allocation");
        }
        ConversationMessage message = new ConversationMessage(
                candidateWithoutSequence.messageId(), threadId, sequence,
                candidateWithoutSequence.role(), candidateWithoutSequence.content(),
                candidateWithoutSequence.runId(), candidateWithoutSequence.success(),
                candidateWithoutSequence.errorCode(), candidateWithoutSequence.runStatus(),
                candidateWithoutSequence.deduplicationKey(), candidateWithoutSequence.createdAt());
        jdbcTemplate.update(
                "INSERT INTO agent_messages (" + MESSAGE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                message.messageId(), message.threadId(), message.sequenceNo(), message.role().name(),
                message.content(), message.runId(), message.success(), message.errorCode(),
                message.runStatus() == null ? null : message.runStatus().name(),
                message.deduplicationKey(), Timestamp.from(message.createdAt()));
        return message;
    }

    private ConversationMessage findExistingByDedup(String threadId, String deduplicationKey) {
        return jdbcTemplate.query(
                "SELECT " + MESSAGE_COLUMNS
                        + " FROM agent_messages WHERE thread_id = ? AND deduplication_key = ?",
                rs -> rs.next() ? mapMessage(rs) : null, threadId, deduplicationKey);
    }

    private boolean samePayload(ConversationMessage left, ConversationMessage right) {
        return left.role() == right.role()
                && left.content().equals(right.content())
                && left.runId().equals(right.runId())
                && Objects.equals(left.success(), right.success())
                && Objects.equals(left.errorCode(), right.errorCode())
                && left.runStatus() == right.runStatus();
    }

    private void touchThread(String threadId, String userId, Instant at) {
        jdbcTemplate.update(
                "UPDATE agent_threads SET last_message_at = ?, updated_at = ? WHERE thread_id = ? AND user_id = ?",
                Timestamp.from(at), Timestamp.from(at), threadId, userId);
    }

    private ConversationThread requireOwnedParticipantForUpdate(
            String threadId, String userId,
            ConversationParticipantType expectedType, String expectedName) {
        ConversationThread thread = jdbcTemplate.query(
                "SELECT " + THREAD_COLUMNS
                        + " FROM agent_threads WHERE thread_id = ? AND user_id = ? FOR UPDATE",
                rs -> rs.next() ? mapThread(rs) : null, threadId, userId);
        if (thread == null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for user");
        }
        if (expectedType == null || thread.participantType() != expectedType
                || !thread.participantName().equals(normalize(expectedName, "participantName"))) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_PARTICIPANT_MISMATCH,
                    "Conversation belongs to a different participant");
        }
        return thread;
    }

    private void requireOwned(String threadId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_threads WHERE thread_id = ? AND user_id = ?",
                Integer.class, threadId, userId);
        if (count == null || count == 0) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                    "Conversation thread not found for user");
        }
    }

    private ConversationThread mapThread(ResultSet rs) throws SQLException {
        return new ConversationThread(rs.getString("thread_id"), rs.getString("user_id"),
                rs.getString("title"), rs.getBoolean("pinned"), rs.getBoolean("archived"),
                ConversationParticipantType.valueOf(rs.getString("participant_type")),
                rs.getString("participant_name"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_message_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ConversationMessage mapMessage(ResultSet rs) throws SQLException {
        String status = rs.getString("run_status");
        return new ConversationMessage(rs.getString("message_id"), rs.getString("thread_id"),
                rs.getLong("sequence_no"), ConversationMessageRole.valueOf(rs.getString("role")),
                rs.getString("content"), rs.getString("run_id"),
                rs.getObject("success", Boolean.class), rs.getString("error_code"),
                status == null ? null : RunStatus.valueOf(status), rs.getString("deduplication_key"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, field + " must not be blank");
        }
        return value.trim();
    }
}
