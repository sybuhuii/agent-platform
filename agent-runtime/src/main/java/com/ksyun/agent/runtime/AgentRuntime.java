package com.ksyun.agent.runtime;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.approval.ApprovalDecision;
import com.ksyun.agent.core.run.RunContext;

/**
 * Agent 运行时接口。
 * <p>
 * 当前只定义接口，不创建伪造的完整实现。
 */
public interface AgentRuntime {

    /**
     * 启动 Agent 执行。
     */
    AgentResult start(AgentTask task, RunContext context);

    /**
     * 恢复被中断的 Agent 执行（如审批通过后恢复）。
     * <p>
     * 第3批实现恢复流程，本批不实现。
     */
    AgentResult resume(String runId, ApprovalDecision decision, RunContext context);
}
