package com.ksyun.agent.runtime.hitl;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

/** 只承载 HITL，不参与本项目的 ReAct 或 Supervisor 编排。 */
public interface LangChain4jApprovalWorkflow extends AgenticScopeAccess {

    @Agent("Wait for an authenticated human approval decision")
    void awaitDecision(
            @MemoryId String approvalId,
            @V("approvalId") String stateApprovalId
    );
}