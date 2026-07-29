package com.ksyun.agent.api.dto;

/**
 * 正式 Agent 调用请求 DTO。
 * <p>
 * 不允许客户端提交 RunContext、安全身份、systemPrompt、allowedTools 或 maxIterations。
 * <p>
 * threadId 可选：
 * - null 或空白表示新线程
 * - 非空时表示续接已有线程
 * - 不得增加 runId 字段
 * - 不得增加 userId 字段
 * - 不得增加 messages 字段
 * - 不得增加 contextSnapshot 字段
 * - 不得增加 Checkpoint 字段
 * - threadId 格式校验由应用服务统一完成
 */
public record AgentInvokeRequest(
        String agentName,
        String message,
        String threadId
) {
}
