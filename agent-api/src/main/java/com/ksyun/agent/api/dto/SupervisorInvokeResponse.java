package com.ksyun.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 正式 Supervisor 调用响应 DTO。
 * <p>
 * 不暴露完整 systemPrompt、SpringAI 或 LangGraph4j 对象、
 * 消息历史、ToolCall 参数、子 Agent 结果列表或内部 State。
 * 不返回 sessionId。不返回 RunContext。
 * <p>
 * Phase9 Batch5 新增字段：
 * - approvalRunId: 子 Agent 的 runId（SUSPENDED 时非空，前端审批时使用此 runId）
 * - parentRunId: 父 Supervisor 的 runId（SUSPENDED 时等于 runId）
 * - isNested: 是否为嵌套 Supervisor 暂停（SUSPENDED 时为 true）
 * <p>
 * 前端审批时使用 approvalRunId（子 runId）作为审批路径参数，
 * 不使用 runId（父 runId），因为审批对象是子 Checkpoint。
 */
public record SupervisorInvokeResponse(
        String runId,
        String threadId,
        String supervisorName,
        boolean success,
        String content,
        String errorCode,
        List<String> evidence,
        Map<String, Object> metadata,
        String approvalRunId,
        String parentRunId,
        boolean isNested
) {
}
