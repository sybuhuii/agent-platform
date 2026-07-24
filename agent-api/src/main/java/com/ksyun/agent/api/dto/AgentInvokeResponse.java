package com.ksyun.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 正式 Agent 调用响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt、SpringAI 或 LangGraph4j 对象、
 * 消息历史、ToolCall 参数或内部 State。不返回 sessionId。
 * 不返回 RunContext。
 */
public record AgentInvokeResponse(
        String runId,
        String threadId,
        String agentName,
        boolean success,
        String content,
        String errorCode,
        List<String> evidence,
        Map<String, Object> metadata
) {
}
