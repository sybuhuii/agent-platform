package com.ksyun.agent.application.supervisor;

import com.ksyun.agent.core.agent.AgentResult;

/**
 * 开发环境 Supervisor 执行结果。
 * <p>
 * 不可变，不包含 LangGraph4j 或 SpringAI 类型。
 */
public record SupervisorDevRunResult(
        String runId,
        String threadId,
        String supervisorName,
        AgentResult result
) {
}
