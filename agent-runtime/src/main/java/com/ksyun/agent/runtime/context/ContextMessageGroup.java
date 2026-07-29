package com.ksyun.agent.runtime.context;

import com.ksyun.agent.core.message.AgentMessage;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 上下文消息分组，不可变。
 * <p>
 * 约束：
 * - SYSTEM 组包含单条 System 消息
 * - NORMAL 组通常包含单条普通用户或助手消息
 * - TOOL_INTERACTION 组包含发出 ToolCall 的 Assistant 消息及全部对应 ToolAgentMessage
 * - 原子组只能整体保留或整体移除
 * - 组内消息保持原始顺序
 * - 不得修改原 AgentMessage
 * - 不得复制 ToolCall ID
 * - 不依赖 Spring
 * - 仅供上下文运行时使用，不暴露给 HTTP 接口
 */
public record ContextMessageGroup(
        List<AgentMessage> messages,
        int startIndex,
        int endIndex,
        ContextMessageGroupType groupType,
        boolean atomic
) {

    public ContextMessageGroup {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(groupType, "groupType must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalArgumentException(
                    "invalid index range: startIndex=" + startIndex + ", endIndex=" + endIndex);
        }
        messages = List.copyOf(messages);
    }
}
