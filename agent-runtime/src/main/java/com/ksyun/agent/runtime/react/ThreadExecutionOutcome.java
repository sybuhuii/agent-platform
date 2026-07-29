package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;

import java.util.Objects;
import java.util.Optional;

/**
 * 线程执行结果，不可变。
 * <p>
 * 包含 AgentResult 和可选的稳定线程会话状态。
 * <p>
 * 语义：
 * - result 不能为空，表示本次执行最终结果
 * - conversationState 不得为 null，使用 Optional.empty() 表示无稳定状态
 * - 成功稳定终态时 conversationState 存在
 * - SUSPENDED 时 conversationState 为空
 * - FAILED 时 conversationState 为空
 * - 不存在稳定状态时不得伪造空 ThreadConversationState
 * <p>
 * 不包含 ReactAgentState、CompiledGraph、UserSession、RunContext、
 * Session ID、Checkpoint。
 * 不依赖 Spring。
 */
public record ThreadExecutionOutcome(
        AgentResult result,
        Optional<ThreadConversationState> conversationState
) {

    public ThreadExecutionOutcome {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(conversationState, "conversationState must not be null");
    }
}
