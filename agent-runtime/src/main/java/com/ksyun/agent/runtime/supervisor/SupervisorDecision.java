package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentTask;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Supervisor 决策结果，不可变。
 * <p>
 * DISPATCH 时 tasks 不能为空；FINISH 时 tasks 必须为空且 finalAnswer 不能为空。
 * 不得包含 SpringAI 类型、CompiledGraph 或节点对象。
 * 不得创建反思 ToolCall。
 *
 * @param action          决策动作，不能为空
 * @param tasks           待分派的子 Agent 任务列表，不可变
 * @param decisionSummary 简洁决策依据，不保存冗长推理过程
 * @param finalAnswer     最终回答，FINISH 时不能为空
 */
public record SupervisorDecision(
        SupervisorAction action,
        List<AgentTask> tasks,
        String decisionSummary,
        String finalAnswer
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SupervisorDecision {
        if (action == null) {
            throw new IllegalArgumentException("SupervisorDecision action must not be null");
        }
        tasks = tasks != null ? Collections.unmodifiableList(tasks) : List.of();
        if (action == SupervisorAction.DISPATCH && tasks.isEmpty()) {
            throw new IllegalArgumentException("SupervisorDecision DISPATCH requires at least one task");
        }
        if (action == SupervisorAction.FINISH && !tasks.isEmpty()) {
            throw new IllegalArgumentException("SupervisorDecision FINISH requires empty tasks");
        }
        if (action == SupervisorAction.FINISH && (finalAnswer == null || finalAnswer.isBlank())) {
            throw new IllegalArgumentException("SupervisorDecision FINISH requires non-blank finalAnswer");
        }
    }
}
