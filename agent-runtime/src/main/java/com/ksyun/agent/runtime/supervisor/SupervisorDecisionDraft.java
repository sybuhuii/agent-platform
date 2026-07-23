package com.ksyun.agent.runtime.supervisor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 未经完全信任和校验的模型决策草稿。
 * <p>
 * 只表示模型输出的原始决策，不得放入 agent-core。
 * 经过 ReasonNode 校验和规范化后，才能转换为正式 SupervisorDecision 和 AgentTask。
 * 集合使用不可变快照，null 集合按空集合处理。
 * 不得包含 taskId、runId 或安全身份。
 * 不得包含 SpringAI、Jackson 或 LangGraph4j 类型。
 *
 * @param action          决策动作
 * @param tasks           任务草稿列表，不可变
 * @param decisionSummary 简洁决策摘要
 * @param finalAnswer     最终回答
 */
public record SupervisorDecisionDraft(
        SupervisorAction action,
        List<SupervisorTaskDraft> tasks,
        String decisionSummary,
        String finalAnswer
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SupervisorDecisionDraft {
        tasks = tasks != null ? Collections.unmodifiableList(tasks) : List.of();
    }
}
