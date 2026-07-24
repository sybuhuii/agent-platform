package com.ksyun.agent.application.agent;

import com.ksyun.agent.core.agent.AgentResult;

/**
 * 正式认证 Agent 执行结果。
 * <p>
 * 不可变。不包含 sessionId 或 RunContext。
 */
public record AuthenticatedAgentRunResult(
        String runId,
        String threadId,
        String agentName,
        AgentResult result
) {
}
