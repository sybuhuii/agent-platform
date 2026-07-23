package com.ksyun.agent.application.react;

import com.ksyun.agent.core.agent.AgentResult;

/**
 * 开发环境 ReAct 执行结果。
 * <p>
 * 不可变，不包含 LangGraph4j 或 SpringAI 类型。
 */
public record ReactDevRunResult(
        String runId,
        String threadId,
        String agentName,
        AgentResult result
) {
}
