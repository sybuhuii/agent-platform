package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import org.bsc.langgraph4j.action.EdgeAction;

import java.util.List;

import static com.ksyun.agent.runtime.supervisor.SupervisorNodeNames.*;

/**
 * Supervisor Dispatch 后路由器，根据子 Agent 执行状态决定路由目标。
 * <p>
 * 实现 LangGraph4j {@link EdgeAction}，由 GraphFactory 通过 edge_async 注册。
 * 纯 Java 实现，不调用模型、工具、子 Agent，不访问 Spring Bean，不保存 Checkpoint。
 * <p>
 * 路由规则：
 * 1. 存在 SUSPENDED_CHILDREN → SUSPEND
 * 2. Dispatch 自身状态非法（缺少任务、结构损坏）→ FAILURE
 * 3. 否则 → AGGREGATE_RESULTS（某个子 Agent 普通 FAILED 仍可进入 Aggregate）
 * <p>
 * 禁止从 Dispatch 直接返回 Reason。
 */
public class SupervisorDispatchRouter implements EdgeAction<SupervisorAgentState> {

    @Override
    public String apply(SupervisorAgentState state) throws Exception {
        List<SupervisorChildExecution> suspendedChildren =
                SupervisorStateKeys.getSuspendedChildren(state);

        // 优先级1：存在暂停子任务 → SUSPEND
        if (suspendedChildren != null && !suspendedChildren.isEmpty()) {
            return SUSPEND;
        }

        // 优先级2：Dispatch 自身状态非法 → FAILURE
        // 检查是否存在结构损坏（如 DISPATCH_TASKS 存在但为空，同时 PENDING_TASKS 非空）
        List<SupervisorChildExecution> dispatchTasks =
                SupervisorStateKeys.getDispatchTasks(state);
        List<AgentTask> pendingTasks = SupervisorStateKeys.getPendingTasks(state);

        if (pendingTasks != null && !pendingTasks.isEmpty()
                && (dispatchTasks == null || dispatchTasks.isEmpty())) {
            // pendingTasks 非空但 dispatchTasks 为空，Dispatch 没有正常执行
            return FAILURE;
        }

        // 优先级3：正常流程 → AGGREGATE_RESULTS
        // 某个子 Agent 普通 FAILED 仍可进入 Aggregate，让 Supervisor 处理失败结果
        return AGGREGATE_RESULTS;
    }
}
