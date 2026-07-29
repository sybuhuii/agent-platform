package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;

import java.util.Optional;

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

    /**
     * 执行线程级别的 Agent ReAct 循环。
     * <p>
     * 新增方法，支持线程续接：
     * - previousState 为空时创建新线程 State
     * - previousState 存在时创建续接 State
     * - 执行完成后判断是否为稳定终态
     * - 稳定时提取 ThreadConversationState
     * - 不稳定时 conversationState 为空
     * <p>
     * 不得在 Engine 中访问 CheckpointStore。
     * 不得在 Engine 中保存 THREAD_MEMORY。
     * 不得在 Engine 中访问 SessionStore。
     * 不得在 Engine 中生成 threadId 或 runId。
     *
     * @param definition    Agent 定义
     * @param task          Agent 任务
     * @param context       运行上下文
     * @param previousState 上一次稳定线程状态，Optional.empty 表示新线程
     * @return 线程执行结果
     */
    ThreadExecutionOutcome executeThread(
            AgentDefinition definition,
            AgentTask task,
            RunContext context,
            Optional<ThreadConversationState> previousState
    );
}
