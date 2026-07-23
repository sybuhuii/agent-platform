package com.ksyun.agent.api.dto;

import java.util.Set;

/**
 * Supervisor 元信息响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt、Java 实现类名或模型配置。
 */
public record SupervisorInfoResponse(
        String name,
        String description,
        Set<String> memberAgents,
        int maxIterations
) {
}
