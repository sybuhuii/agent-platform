package com.ksyun.agent.runtime.supervisor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 未经完全信任和校验的模型任务草稿。
 * <p>
 * 只表示模型输出的原始任务信息，不得放入 agent-core。
 * 经过 ReasonNode 校验和规范化后，才能转换为正式 AgentTask。
 * 集合使用不可变快照，null 集合按空集合处理。
 * 不得包含 taskId、runId 或安全身份。
 * 不得包含 SpringAI、Jackson 或 LangGraph4j 类型。
 *
 * @param agentName  目标 Agent 名称
 * @param instruction 任务指令
 * @param context    任务上下文，不可变
 */
public record SupervisorTaskDraft(
        String agentName,
        String instruction,
        Map<String, Object> context
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SupervisorTaskDraft {
        context = context != null ? Collections.unmodifiableMap(context) : Map.of();
    }
}
