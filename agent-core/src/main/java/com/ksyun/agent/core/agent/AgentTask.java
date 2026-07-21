package com.ksyun.agent.core.agent;

import java.util.Collections;
import java.util.Map;

/**
 * Agent 执行任务。
 *
 * @param taskId     任务 ID
 * @param agentName  目标 Agent 名称
 * @param instruction 用户指令
 * @param context    任务上下文，不可变
 */
public record AgentTask(
        String taskId,
        String agentName,
        String instruction,
        Map<String, Object> context
) {

    public AgentTask {
        context = context == null ? Map.of() : Collections.unmodifiableMap(context);
    }
}
