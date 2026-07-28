package com.ksyun.agent.application.context;

import java.util.Objects;

/**
 * 长上下文演示命令，不可变。
 * <p>
 * 约束：
 * - rounds 范围建议为 5 至 80
 * - charactersPerMessage 范围建议为 32 至 512
 * - finalQuestion 不能为空并限制长度
 * - 不得允许客户端提交 System 消息
 * - 不得允许客户端提交任意角色消息列表
 * - 不得允许客户端提交 ToolCall
 * - 不得允许客户端提交 userId、roles 和 permissions
 * - 不得允许客户端提交 maxContextTokens 和摘要配置
 * - 框架配置是唯一上下文预算来源
 */
public record ContextDemoCommand(
        int rounds,
        int charactersPerMessage,
        boolean includeToolInteractions,
        boolean invokeModel,
        String finalQuestion
) {

    public ContextDemoCommand {
        if (rounds < 5 || rounds > 80) {
            throw new IllegalArgumentException("rounds must be between 5 and 80, got: " + rounds);
        }
        if (charactersPerMessage < 32 || charactersPerMessage > 512) {
            throw new IllegalArgumentException(
                    "charactersPerMessage must be between 32 and 512, got: " + charactersPerMessage);
        }
        Objects.requireNonNull(finalQuestion, "finalQuestion must not be null");
        String trimmed = finalQuestion.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("finalQuestion must not be blank");
        }
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("finalQuestion must not exceed 500 characters");
        }
        finalQuestion = trimmed;
    }
}
