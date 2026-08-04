package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.MemoryContextAgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 恢复持久化 ContextWindowSnapshot 中不允许持久化的临时协议消息。
 *
 * <p>System Prompt 必须使用当前 Definition 重新构建，不能从数据库读取旧值。
 * MemoryContextAgentMessage 始终由当前用户的 MemoryStore 重新注入。</p>
 *
 * 负责恢复当前 System Prompt，并清理旧的长期记忆消息；真正的长期记忆查询和注入由后面的 Memory 上下文组件完成
 */
public final class ContextWindowSnapshotRestorer {

    private ContextWindowSnapshotRestorer() {
    }

    /**
     * THREAD_MEMORY 的 consumedHistoryMessageCount 保留了原 System 消息的位置，
     * 因此恢复 System 消息时不能再次增加 consumed 数量。
     */
    public static ContextWindowSnapshot forThreadContinuation(
            ContextWindowSnapshot snapshot,
            String currentSystemPrompt
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return restore(snapshot, currentSystemPrompt,
                snapshot.consumedHistoryMessageCount());
    }

    /**
     * HITL payload builder 在过滤 System 消息时已经从 consumed 数量中减去了该消息，
     * 因此恢复当前 System 消息时需要加回一个 consumed 位置。
     */
    public static ContextWindowSnapshot forHitlResume(
            ContextWindowSnapshot snapshot,
            String currentSystemPrompt
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        int restoredConsumed = snapshot.consumedHistoryMessageCount();
        if (currentSystemPrompt != null && !currentSystemPrompt.isBlank()) {
            restoredConsumed++;
        }

        return restore(snapshot, currentSystemPrompt, restoredConsumed);
    }

    private static ContextWindowSnapshot restore(
            ContextWindowSnapshot snapshot,
            String currentSystemPrompt,
            int restoredConsumed
    ) {
        List<AgentMessage> restoredMessages =
                new ArrayList<>(snapshot.windowMessages().size() + 1);

        if (currentSystemPrompt != null && !currentSystemPrompt.isBlank()) {
            restoredMessages.add(new SystemAgentMessage(currentSystemPrompt));
        }

        for (AgentMessage message : snapshot.windowMessages()) {
            if (!(message instanceof SystemAgentMessage)
                    && !(message instanceof MemoryContextAgentMessage)) {
                restoredMessages.add(message);
            }
        }

        return new ContextWindowSnapshot(
                List.copyOf(restoredMessages),
                restoredConsumed,
                snapshot.processingSequence(),
                snapshot.latestTrace(),
                snapshot.updatedAt());
    }
}