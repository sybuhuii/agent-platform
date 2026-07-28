package com.ksyun.agent.core.message;

import java.time.Instant;
import java.util.Objects;

/**
 * 框架生成的摘要消息。
 * <p>
 * 由上下文摘要流水线生成，用于压缩较旧的非 System 消息。
 * 后续映射到 LLM 时视为 System 角色，但本批不修改 Spring AI Mapper。
 * <p>
 * 约束：
 * - content 不能为空且必须 trim
 * - generatedAt 不能为空
 * - 不可变类型
 * - 不包含原始消息列表
 * - 不包含完整 Prompt
 * - 不包含模型响应对象
 * - 不包含 RunContext
 * - 不包含用户身份和权限
 * - 不包含 Spring AI 类型
 * - 不得使用普通 SystemAgentMessage 替代
 *   （需要区分原始 System 消息与框架摘要）
 */
public record SummaryAgentMessage(
        String content,
        Instant generatedAt
) implements AgentMessage {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SummaryAgentMessage {
        Objects.requireNonNull(content, "content must not be null");
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("content must not be blank after trimming");
        }
        content = trimmed;
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    }
}
