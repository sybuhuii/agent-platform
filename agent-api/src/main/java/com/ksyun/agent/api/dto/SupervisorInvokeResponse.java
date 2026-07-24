package com.ksyun.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 正式 Supervisor 调用响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt、SpringAI 或 LangGraph4j 对象、
 * 消息历史、ToolCall 参数、子 Agent 结果列表或内部 State。
 * 不返回 sessionId。不返回 RunContext。
 */
public record SupervisorInvokeResponse(
        String runId,
        String threadId,
        String supervisorName,
        boolean success,
        String content,
        String errorCode,
        List<String> evidence,
        Map<String, Object> metadata
) {
}
