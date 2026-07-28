package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;

import java.util.Collection;

/**
 * Token 计数器 SPI。
 * <p>
 * 位于 agent-core，不依赖模型 API、Tokenizer 库或 Spring。
 * <p>
 * 约束：
 * - 不依赖 tiktoken、huggingface-tokenizers 或其他第三方 Tokenizer
 * - 默认实现使用启发式估算
 * - 不缓存计数结果
 * - 不持有 Session、ModelClient、Registry 或 Gateway
 * - 不调用模型
 * - 线程安全、无状态
 * - 单条消息不能为空
 * - 消息集合不能包含 null
 * - 空集合返回 0
 * - 返回值不得为负数
 * - 集合计数必须包含每条消息固定开销
 * - 工具名称、ToolCall 参数和 ToolResult 内容必须计入
 * - 不得只统计 content 字段
 * - 不得调用 LLM 完成计数
 * - 不得把模型 API Key 传入计数器
 */
public interface TokenCounter {

    /**
     * 估算单条消息的 Token 数量。
     *
     * @param message 消息，不能为 null
     * @return 估算的 Token 数量，>= 0
     */
    int count(AgentMessage message);

    /**
     * 估算消息集合的 Token 数量。
     *
     * @param messages 消息集合，不能为 null，不能包含 null 元素
     * @return 估算的 Token 数量，>= 0；空集合返回 0
     */
    int count(Collection<? extends AgentMessage> messages);
}
