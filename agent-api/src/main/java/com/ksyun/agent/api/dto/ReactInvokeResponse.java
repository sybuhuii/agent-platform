package com.ksyun.agent.api.dto;

import com.ksyun.agent.core.run.RunStatus;

import java.util.List;
import java.util.Map;

/**
 * ReAct 开发调用响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt、SpringAI 或 LangGraph4j 对象。
 * SUSPENDED 正常返回 200。
 */
public record ReactInvokeResponse(
        String runId,
        String threadId,
        String agentName,
        boolean success,
        String content,
        String errorCode,
        List<String> evidence,
        Map<String, Object> metadata,
        RunStatus status
) {
}
