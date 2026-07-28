package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.context.ContextManagementPolicy;

import java.util.Set;

/**
 * Agent 元信息响应 DTO。
 * <p>
 * 不暴露 systemPrompt 完整内容、内部实现类名和权限敏感数据。
 */
public record AgentInfoResponse(
        String name,
        String description,
        Set<String> allowedTools,
        int maxIterations,
        ContextManagementInfo contextManagement
) {

    /**
     * 上下文管理信息摘要。
     */
    public record ContextManagementInfo(
            String trimStrategy,
            int maxMessages,
            boolean systemPromptAlwaysPreserved,
            boolean latestUserInputPreserved,
            boolean atomicGroupOvershoot
    ) {}

    /**
     * 从 AgentDefinition 构造响应。
     */
    public static AgentInfoResponse from(com.ksyun.agent.core.agent.AgentDefinition def) {
        ContextManagementPolicy policy = def.contextManagementPolicy();
        ContextManagementInfo cmInfo = new ContextManagementInfo(
                policy.trimStrategy().name(),
                policy.maxMessages(),
                policy.systemPromptAlwaysPreserved(),
                policy.latestUserInputPreserved(),
                policy.atomicGroupOvershoot()
        );
        return new AgentInfoResponse(
                def.name(),
                def.description(),
                def.allowedTools(),
                def.maxIterations(),
                cmInfo
        );
    }
}
