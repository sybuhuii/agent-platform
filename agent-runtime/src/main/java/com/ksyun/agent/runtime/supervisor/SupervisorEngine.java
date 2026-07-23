package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;

/**
 * 多 Agent Supervisor 统一执行入口。
 * <p>
 * 接口不得暴露 LangGraph4j 类型。
 * 不实现 resume。
 * 最终结果继续使用现有 AgentResult，不建立第二套 SupervisorResult。
 */
public interface SupervisorEngine {

    /**
     * 执行 Supervisor 多 Agent 调度循环。
     *
     * @param definition Supervisor 定义
     * @param rootTask   根任务
     * @param context    运行上下文
     * @return 最终执行结果
     */
    AgentResult execute(SupervisorDefinition definition, AgentTask rootTask, RunContext context);
}
