package com.ksyun.agent.core.agent;

import com.ksyun.agent.core.context.ContextManagementPolicy;

import java.io.Serializable;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Agent 定义。
 *
 * @param name          Agent 名称，不能为空
 * @param description   Agent 描述
 * @param systemPrompt  系统提示词
 * @param allowedTools  允许使用的工具名称集合，不可变
 * @param maxIterations 最大迭代次数，必须大于 0
 * @param contextManagementPolicy 上下文管理策略，不得为 null
 */
public record AgentDefinition(
        String name,
        String description,
        String systemPrompt,
        Set<String> allowedTools,
        int maxIterations,
        ContextManagementPolicy contextManagementPolicy
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 2L;

    public AgentDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be greater than 0");
        }
        allowedTools = allowedTools == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedTools));
        // contextManagementPolicy 默认值由兼容构造器处理
        Objects.requireNonNull(contextManagementPolicy, "contextManagementPolicy must not be null");
    }

    /**
     * 兼容构造器：不指定上下文管理策略时使用默认值。
     */
    public AgentDefinition(String name, String description, String systemPrompt,
                            Set<String> allowedTools, int maxIterations) {
        this(name, description, systemPrompt, allowedTools, maxIterations, ContextManagementPolicy.DEFAULT);
    }
}
