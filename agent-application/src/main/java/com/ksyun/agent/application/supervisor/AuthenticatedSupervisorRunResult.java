package com.ksyun.agent.application.supervisor;

import com.ksyun.agent.core.agent.AgentResult;

/**
 * 正式认证 Supervisor 执行结果。
 * <p>
 * 不可变。不包含 sessionId 或 RunContext。
 */
public record AuthenticatedSupervisorRunResult(
        String runId,
        String threadId,
        String supervisorName,
        AgentResult result
) {
}
