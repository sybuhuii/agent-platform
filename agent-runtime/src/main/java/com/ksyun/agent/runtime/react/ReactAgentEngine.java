package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.run.RunContext;

/**
 * 单 Agent ReAct 执行引擎接口。
 * <p>
 * 这是单 Agent ReAct 执行入口。
 * 不暴露 LangGraph4j 类型。
 * 不实现 resume（审批恢复属于 HITL 阶段）。
 */
public interface ReactAgentEngine {

    /**
     * 执行单 Agent ReAct 循环。
     *
     * @param definition Agent 定义
     * @param task       Agent 任务
     * @param context    运行上下文
     * @return Agent 执行结果
     */
    AgentResult execute(AgentDefinition definition, AgentTask task, RunContext context);
}
