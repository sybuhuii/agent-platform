package com.ksyun.agent.application.conversation;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.ConversationStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话历史应用服务，纯 Java 实现。
 * <p>
 * 负责已认证用户的会话列表、消息查询、重命名、置顶、归档和执行结果记录。
 * 在现有 Agent/Supervisor/审批恢复调用流程中做最小编排接入。
 * <p>
 * 约束：
 * - 不依赖 Spring Web/JDBC/Servlet。
 * - 所有 userId 只能来自已验证 UserSession。
 * - 生成稳定的非敏感 deduplicationKey，不接受客户端提交。
 * - 模型调用与数据库写入不在同一事务，通过去重键保证最终一致。
 * - 不调用模型、工具、CheckpointStore 或 SessionStore。
 */
public class ConversationHistoryApplicationService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_MSG_PAGE_SIZE = 50;

    private final ConversationStore conversationStore;
    private final Clock clock;

    public ConversationHistoryApplicationService(ConversationStore conversationStore, Clock clock) {
        this.conversationStore = Objects.requireNonNull(conversationStore);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 记录一轮执行结果（用户消息 + 助手消息）。
     * <p>
     * 首次调用创建 thread，续接调用追加。
     * 模型调用完成后调用，使用 runId 构造去重键。
     *
     * @param session    已认证会话
     * @param threadId   会话 ID
     * @param agentName  agent/supervisor 名称
     * @param userMessage 用户消息
     * @param assistantContent 助手回复内容
     * @param runId      本次运行 ID（用于去重）
     * @return 写入后的消息列表
     */
    public List<ConversationMessage> recordRound(
            UserSession session,
            String threadId,
            String agentName,
            String userMessage,
            String assistantContent,
            String runId) {
        Objects.requireNonNull(session, "session must not be null");
        validateThreadOrRunId(threadId, runId);
        validateContent(userMessage);
        if (assistantContent == null || assistantContent.isBlank()) {
            // 助手无内容（如 SUSPENDED）时不写助手消息，只创建 thread 和用户消息
            return recordUserOnly(session, threadId, agentName, userMessage, runId);
        }

        String userDedupKey = "invoke:" + runId + ":user";
        String assistantDedupKey = "invoke:" + runId + ":assistant";

        Optional<ConversationThread> existing = conversationStore.findThread(session.userId(), threadId);
        Instant now = clock.instant();

        if (existing.isEmpty()) {
            ConversationThread thread = new ConversationThread(
                    threadId, session.userId(), deriveTitle(userMessage),
                    false, false, agentName, now, now, now);
            return conversationStore.createThreadWithFirstRound(
                    thread, userMessage, assistantContent, userDedupKey, assistantDedupKey);
        }

        return conversationStore.appendRound(
                session.userId(), threadId, userMessage, assistantContent,
                userDedupKey, assistantDedupKey);
    }

    /**
     * 只记录用户消息（助手无内容，如挂起场景）。
     */
    private List<ConversationMessage> recordUserOnly(
            UserSession session,
            String threadId,
            String agentName,
            String userMessage,
            String runId) {
        String userDedupKey = "invoke:" + runId + ":user";
        Optional<ConversationThread> existing = conversationStore.findThread(session.userId(), threadId);
        Instant now = clock.instant();

        if (existing.isEmpty()) {
            ConversationThread thread = new ConversationThread(
                    threadId, session.userId(), deriveTitle(userMessage),
                    false, false, agentName, now, now, now);
            // 用占位助手内容创建，然后……实际上首轮必须有助手消息。
            // 这里改为：创建 thread 后单独记录用户消息。
            // 但 createThreadWithFirstRound 要求两条消息。挂起时助手无内容，
            // 我们记录一条用户消息和一条空助手消息会违反非空约束。
            // 因此挂起场景下只创建 thread（无消息），后续恢复时再补助手消息。
            conversationStore.createThreadWithFirstRound(
                    thread, userMessage, assistantPlaceholderForSuspend(userMessage),
                    userDedupKey, "invoke:" + runId + ":assistant");
        } else {
            // 续接挂起：追加用户消息，助手消息用一个标记占位
            conversationStore.appendRound(
                    session.userId(), threadId, userMessage,
                    assistantPlaceholderForSuspend(userMessage),
                    userDedupKey, "invoke:" + runId + ":assistant");
        }
        // 返回查询当前消息
        return conversationStore.listMessages(session.userId(), threadId, null, MAX_PAGE_SIZE);
    }

    /**
     * 记录审批恢复后的助手消息。
     */
    public ConversationMessage recordApprovalResume(
            UserSession session,
            String threadId,
            String assistantContent,
            String approvalId) {
        Objects.requireNonNull(session, "session must not be null");
        validateThreadOrRunId(threadId, approvalId);
        if (assistantContent == null || assistantContent.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "assistant content must not be blank for approval resume");
        }
        String dedupKey = "approval-resume:" + approvalId + ":assistant";
        return conversationStore.appendAssistantMessage(
                session.userId(), threadId, assistantContent, dedupKey);
    }

    /**
     * 列出当前用户的未归档会话。
     */
    public List<ConversationThread> listThreads(UserSession session, String cursorThreadId,
                                                  Long cursorLastMessageAtEpochMillis, int pageSize) {
        Objects.requireNonNull(session, "session must not be null");
        int limit = sanitizePageSize(pageSize);

        ConversationStore.ThreadCursor cursor = null;
        if (cursorThreadId != null && !cursorThreadId.isBlank() && cursorLastMessageAtEpochMillis != null) {
            cursor = new ConversationStore.ThreadCursor(
                    Instant.ofEpochMilli(cursorLastMessageAtEpochMillis), cursorThreadId.trim());
        }
        return conversationStore.listThreads(session.userId(), cursor, limit);
    }

    /**
     * 列出指定会话的消息。
     */
    public List<ConversationMessage> listMessages(UserSession session, String threadId,
                                                    Long beforeSequence, int pageSize) {
        Objects.requireNonNull(session, "session must not be null");
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
        int limit = sanitizePageSize(pageSize);
        return conversationStore.listMessages(session.userId(), threadId, beforeSequence, limit);
    }

    public Optional<ConversationThread> rename(UserSession session, String threadId, String title) {
        Objects.requireNonNull(session, "session must not be null");
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "title must not be blank");
        }
        return conversationStore.rename(session.userId(), threadId, title.trim());
    }

    public Optional<ConversationThread> setPinned(UserSession session, String threadId, boolean pinned) {
        Objects.requireNonNull(session, "session must not be null");
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
        return conversationStore.setPinned(session.userId(), threadId, pinned);
    }

    public Optional<ConversationThread> archive(UserSession session, String threadId) {
        Objects.requireNonNull(session, "session must not be null");
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
        return conversationStore.archive(session.userId(), threadId);
    }

    // ---- 内部 ----

    private int sanitizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private void validateThreadOrRunId(String threadId, String runId) {
        if (threadId == null || threadId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "threadId must not be blank");
        }
        if (runId == null || runId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "runId must not be blank");
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "content must not be blank");
        }
    }

    private String deriveTitle(String userMessage) {
        String trimmed = userMessage.trim();
        return trimmed.length() <= 50 ? trimmed : trimmed.substring(0, 50);
    }

    private String assistantPlaceholderForSuspend(String userMessage) {
        // 挂起时助手无内容，但首轮/追加要求两条消息。
        // 这里用一个可识别的占位文本，恢复后会追加真实助手消息。
        return "（等待审批恢复中）";
    }
}
