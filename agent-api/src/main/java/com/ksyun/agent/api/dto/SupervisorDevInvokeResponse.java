package com.ksyun.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Supervisor 开发调用响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt、SpringAI 或 LangGraph4j 对象、子Agent完整结果列表、
 * 消息历史、ToolCall 参数或内部 State。
 */
public record SupervisorDevInvokeResponse(
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
