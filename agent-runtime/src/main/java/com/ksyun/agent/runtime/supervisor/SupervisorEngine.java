package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.react.ThreadExecutionOutcome;

import java.util.Optional;

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

    /**
     * 执行 Supervisor 线程调用，支持新线程和续接线程。
     * <p>
     * 处理规则：
     * - previousState 为空时创建新 SupervisorAgentState
     * - previousState 存在时创建续接 State
     * - 执行现有 Supervisor 图
     * - 取得最终 SupervisorAgentState
     * - 调用 SupervisorThreadPersistencePolicy 判断是否稳定
     * - 稳定时提取 ThreadConversationState
     * - 不稳定时 conversationState 为空
     * <p>
     * 不得在 SupervisorEngine 中访问 CheckpointStore。
     * 不得在 Engine 中保存 THREAD_MEMORY。
     * 不得生成 threadId 或 runId。
     * 不得修改 Supervisor 路由图。
     * 不得把 previousState 传给子 Agent。
     * 保留原有无状态 execute 方法。
     *
     * @param definition    Supervisor 定义
     * @param task          根任务
     * @param context       运行上下文
     * @param previousState 上一轮线程状态，Optional.empty 表示新线程
     * @return 线程执行结果，包含 AgentResult 和可选 ThreadConversationState
     */
    ThreadExecutionOutcome executeThread(
            SupervisorDefinition definition,
            AgentTask task,
            RunContext context,
            Optional<ThreadConversationState> previousState
    );
}
