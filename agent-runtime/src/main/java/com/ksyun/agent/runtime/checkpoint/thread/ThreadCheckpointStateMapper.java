package com.ksyun.agent.runtime.checkpoint.thread;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 线程 Checkpoint 状态映射器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 将 ThreadConversationState 写入 Checkpoint stateData
 * 2. 从 THREAD_MEMORY Checkpoint 恢复 ThreadConversationState
 * 3. 校验 stateData 中的类型
 * <p>
 * 不得从 ReactAgentState 提取。不得从 SupervisorAgentState 提取。
 * 不得修改 Engine。不得访问 CheckpointStore。
 * 保持无状态和线程安全。
 */
public class ThreadCheckpointStateMapper {

    /**
     * 将 ThreadConversationState 写入 stateData。
     *
     * @param state 线程会话状态
     * @return 不可变 Map，只写入 THREAD_CONVERSATION_STATE
     */
    public Map<String, Object> toStateData(ThreadConversationState state) {
        Objects.requireNonNull(state, "ThreadConversationState must not be null");

        Map<String, Object> data = new HashMap<>();
        data.put(ThreadCheckpointStateKeys.THREAD_CONVERSATION_STATE, state);
        return Collections.unmodifiableMap(data);
    }

    /**
     * 从 THREAD_MEMORY Checkpoint 恢复 ThreadConversationState。
     *
     * @param checkpoint Checkpoint
     * @return 线程会话状态
     * @throws AgentFrameworkException purpose 不是 THREAD_MEMORY、status 不是 COMPLETED、
     *                                  缺失或类型错误时抛 THREAD_CHECKPOINT_INVALID
     */
    public ThreadConversationState fromCheckpoint(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "AgentCheckpoint must not be null");

        // 校验 purpose=THREAD_MEMORY
        if (checkpoint.purpose() != CheckpointPurpose.THREAD_MEMORY) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "Expected purpose THREAD_MEMORY, got " + checkpoint.purpose());
        }

        // 校验 status=COMPLETED
        if (checkpoint.status() != CheckpointStatus.COMPLETED) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_MEMORY Checkpoint status must be COMPLETED, got " + checkpoint.status());
        }

        // 从 stateData 中提取
        Object raw = checkpoint.stateData().get(ThreadCheckpointStateKeys.THREAD_CONVERSATION_STATE);
        if (raw == null) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_CONVERSATION_STATE not found in stateData");
        }

        // 类型校验，不得进行不安全的任意反射转换
        if (!(raw instanceof ThreadConversationState state)) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_CHECKPOINT_INVALID,
                    "THREAD_CONVERSATION_STATE has wrong type: " + raw.getClass().getName());
        }

        return state;
    }
}
