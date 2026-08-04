package com.ksyun.agent.core.store;

import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationThread;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户可见会话历史存储接口。
 * <p>
 * 位于 agent-core，不依赖 Spring、JDBC、模型或 CheckpointStore。
 * <p>
 * 约束：
 * - Store 不生成 threadId、runId、messageId、标题或用户身份。
 * - Store 不调用模型、工具、CheckpointStore 或 SessionStore。
 * - 创建重复 threadId 且内容不一致必须明确冲突，不得覆盖其他会话。
 * - 所有查询和修改都必须包含服务端传入的 userId 归属条件。
 * - 对同一 thread + 相同去重键 + 相同内容按明确幂等语义返回已有消息。
 * - 相同去重键但内容不同必须报告冲突，不能覆盖。
 * - 不接受客户端提交的去重键。
 */
public interface ConversationStore {

    /**
     * 创建会话并记录首轮用户/助手消息。
     * <p>
     * 首次创建：thread 必须不存在；分配两个连续 sequence_no。
     * 重复 threadId 且内容一致：幂等返回已有消息。
     * 重复 threadId 且内容不一致：明确冲突。
     *
     * @param thread            会话线索
     * @param userMessage       首轮用户消息
     * @param assistantMessage  首轮助手消息
     * @param userDedupKey      用户消息去重键
     * @param assistantDedupKey 助手消息去重键
     * @return 写入后的消息列表（用户消息在前）
     */
    List<ConversationMessage> createThreadWithFirstRound(
            ConversationThread thread,
            String userMessage,
            String assistantMessage,
            String userDedupKey,
            String assistantDedupKey);

    /**
     * 在已有会话中原子追加一轮用户/助手消息。
     * <p>
     * 会话必须存在且归属当前 userId；分配两个连续 sequence_no。
     * 去重键命中且内容一致：幂等返回。
     * 去重键命中但内容不一致：冲突。
     *
     * @param userId            用户 ID
     * @param threadId          会话 ID
     * @param userMessage       用户消息内容
     * @param assistantMessage  助手消息内容
     * @param userDedupKey      用户消息去重键
     * @param assistantDedupKey 助手消息去重键
     * @return 写入后的消息列表
     */
    List<ConversationMessage> appendRound(
            String userId,
            String threadId,
            String userMessage,
            String assistantMessage,
            String userDedupKey,
            String assistantDedupKey);

    /**
     * 追加审批恢复后的助手消息。
     * <p>
     * 只追加一个 ASSISTANT 消息并原子更新 thread 时间。
     *
     * @param userId       用户 ID
     * @param threadId     会话 ID
     * @param assistantMessage 助手消息内容
     * @param dedupKey     去重键
     * @return 写入后的消息
     */
    ConversationMessage appendAssistantMessage(
            String userId,
            String threadId,
            String assistantMessage,
            String dedupKey);

    /**
     * 按 userId + threadId 查询会话并校验归属。
     *
     * @param userId   用户 ID
     * @param threadId 会话 ID
     * @return 会话，不存在或不归属返回 Optional.empty()
     */
    Optional<ConversationThread> findThread(String userId, String threadId);

    /**
     * 按用户分页列出未归档会话。
     * <p>
     * 排序：pinned 优先，最近消息优先。
     *
     * @param userId 用户 ID
     * @param before 排序游标（lastMessageAt + threadId），首页为 null
     * @param limit  页大小，由调用方限制上限
     * @return 会话列表
     */
    List<ConversationThread> listThreads(String userId, ThreadCursor before, int limit);

    /**
     * 按 userId + threadId 分页列出消息。
     *
     * @param userId    用户 ID
     * @param threadId  会话 ID
     * @param beforeSequence sequence_no 游标，首页为 null
     * @param limit     页大小
     * @return 消息列表，按 sequence_no 升序
     */
    List<ConversationMessage> listMessages(
            String userId,
            String threadId,
            Long beforeSequence,
            int limit);

    /**
     * 重命名会话。
     *
     * @return 更新后的会话，不存在或不归属返回 Optional.empty()
     */
    Optional<ConversationThread> rename(String userId, String threadId, String title);

    /**
     * 设置/取消置顶。
     *
     * @return 更新后的会话，不存在或不归属返回 Optional.empty()
     */
    Optional<ConversationThread> setPinned(String userId, String threadId, boolean pinned);

    /**
     * 归档会话（逻辑归档）。
     *
     * @return 更新后的会话，不存在或不归属返回 Optional.empty()
     */
    Optional<ConversationThread> archive(String userId, String threadId);

    /**
     * 会话排序游标，不可变。
     * <p>
     * 由 lastMessageAt + threadId 组成，用于游标分页。
     */
    record ThreadCursor(Instant lastMessageAt, String threadId) {
        public ThreadCursor {
            java.util.Objects.requireNonNull(lastMessageAt, "lastMessageAt must not be null");
            java.util.Objects.requireNonNull(threadId, "threadId must not be null");
        }
    }
}
