package com.ksyun.agent.core.agent;

import java.util.Collections;
import java.util.Set;

/**
 * Agent 定义。
 *
 * @param name          Agent 名称，不能为空
 * @param description   Agent 描述
 * @param systemPrompt  系统提示词
 * @param allowedTools  允许使用的工具名称集合，不可变
 * @param maxIterations 最大迭代次数，必须大于 0
 */
public record AgentDefinition(
        String name,
        String description,
        String systemPrompt,
        Set<String> allowedTools,
        int maxIterations
) {

    public AgentDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be greater than 0");
        }
        allowedTools = allowedTools == null ? Set.of() : Collections.unmodifiableSet(allowedTools);
    }
}
