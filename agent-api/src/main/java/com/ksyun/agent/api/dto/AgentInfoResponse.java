package com.ksyun.agent.api.dto;

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
        int maxIterations
) {
}
