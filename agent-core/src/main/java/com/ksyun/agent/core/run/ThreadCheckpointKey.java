package com.ksyun.agent.core.run;

import java.util.Objects;

/**
 * 线程 Checkpoint 辅助索引复合键，不可变。
 * <p>
 * 只负责索引用户线程和用途，不包含 sessionId、runId 或 executionType。
 * 适合作为 ConcurrentHashMap 的 Key。
 * 不得使用 userId + ":" + threadId 字符串拼接代替。
 * 不依赖 Spring。
 */
public record ThreadCheckpointKey(
        String userId,
        String threadId,
        CheckpointPurpose purpose
) {

    public ThreadCheckpointKey {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");

        userId = userId.trim();
        threadId = threadId.trim();

        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
    }
}
