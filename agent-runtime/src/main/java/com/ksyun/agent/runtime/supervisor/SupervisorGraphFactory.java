package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.runtime.supervisor.node.*;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Map;

import static com.ksyun.agent.runtime.supervisor.SupervisorNodeNames.*;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;
import static java.util.Map.entry;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Supervisor 图工厂。
 * <p>
 * 根据节点实现构建 Supervisor StateGraph。
 * 通过构造器接收各节点，不从 Spring 容器主动查找。
 * 图工厂本身不保存某次运行的可变 State。
 * <p>
 * 图结构：
 * START → supervisor_reason → 条件路由
 * DISPATCH → dispatch_agents → SupervisorDispatchRouter
 *   ├─ aggregate_results → supervisor_reason
 *   ├─ suspend → END
 *   └─ failure → END
 * COMPLETE → complete → END
 * MAX_ITERATIONS → max_iterations_fallback → END
 * FAIL → failure → END
 */
public class SupervisorGraphFactory {

    private final SupervisorReasonNode reasonNode;
    private final SupervisorDispatchNode dispatchNode;
    private final SupervisorAggregateNode aggregateNode;
    private final SupervisorCompleteNode completeNode;
    private final SupervisorMaxIterationsNode maxIterationsNode;
    private final SupervisorFailureNode failureNode;
    private final SupervisorSuspendNode suspendNode;
    private final SupervisorRouter router;
    private final SupervisorDispatchRouter dispatchRouter;

    public SupervisorGraphFactory(
            SupervisorReasonNode reasonNode,
            SupervisorDispatchNode dispatchNode,
            SupervisorAggregateNode aggregateNode,
            SupervisorCompleteNode completeNode,
            SupervisorMaxIterationsNode maxIterationsNode,
            SupervisorFailureNode failureNode,
            SupervisorSuspendNode suspendNode,
            SupervisorRouter router,
            SupervisorDispatchRouter dispatchRouter
    ) {
        this.reasonNode = reasonNode;
        this.dispatchNode = dispatchNode;
        this.aggregateNode = aggregateNode;
        this.completeNode = completeNode;
        this.maxIterationsNode = maxIterationsNode;
        this.failureNode = failureNode;
        this.suspendNode = suspendNode;
        this.router = router;
        this.dispatchRouter = dispatchRouter;
    }

    /**
     * 构建 Channel 定义，配置各状态字段的合并语义。
     * <p>
     * 合并语义说明：
     * - supervisorMessages: 追加
     * - agentResults: 追加
     * - decision: 覆盖
     * - pendingTasks: 覆盖
     * - latestAgentResults: 覆盖
     * - iteration: 覆盖，不使用 Integer::sum
     * - finalResult: 覆盖
     * - stopReason: 覆盖
     * - failureErrorCode: 覆盖
     * - failureMessage: 覆盖
     * - supervisorDefinition、rootTask、runContext: 初始化后保持稳定，覆盖
     * - dispatchTasks: 覆盖（本批完整任务状态表）
     * - suspendedChildren: 覆盖
     * - runStatus: 覆盖
     * - checkpointId: 覆盖
     * <p>
     * 节点返回追加字段时只能返回本轮新增内容，不得返回完整历史。
     */
    private Map<String, Channel<?>> buildChannels() {
        return Map.ofEntries(
                // 追加语义
                entry(SUPERVISOR_MESSAGES, Channels.<Object>appender(ArrayList::new)),
                entry(AGENT_RESULTS, Channels.<Object>appender(ArrayList::new)),

                // 覆盖语义
                entry(PENDING_TASKS, Channels.base((oldVal, newVal) -> newVal)),
                entry(LATEST_AGENT_RESULTS, Channels.base((oldVal, newVal) -> newVal)),
                entry(DECISION, Channels.base((oldVal, newVal) -> newVal)),

                entry(ITERATION, Channels.base(() -> 0)),

                // Channels without default providers — initial values are always
                // supplied explicitly by the SupervisorEngine implementation.
                entry(FINAL_RESULT, Channels.base((oldVal, newVal) -> newVal)),
                entry(STOP_REASON, Channels.base((oldVal, newVal) -> newVal)),
                entry(FAILURE_MESSAGE, Channels.base((oldVal, newVal) -> newVal)),
                entry(FAILURE_ERROR_CODE, Channels.base((oldVal, newVal) -> newVal)),

                entry(SUPERVISOR_DEFINITION, Channels.base((oldVal, newVal) -> newVal)),
                entry(ROOT_TASK, Channels.base((oldVal, newVal) -> newVal)),
                entry(RUN_CONTEXT, Channels.base((oldVal, newVal) -> newVal)),

                // Phase7 Batch4 上下文窗口 Channel（覆盖语义）
                entry(CONTEXT_WINDOW_SNAPSHOT, Channels.base((oldVal, newVal) -> newVal)),
                entry(LATEST_CONTEXT_TRACE, Channels.base((oldVal, newVal) -> newVal)),
                entry(LATEST_MEMORY_CONTEXT_TRACE, Channels.base((oldVal, newVal) -> newVal)),

                // Phase9 Batch2 Supervisor 暂停状态 Channel（覆盖语义）
                entry(RUN_STATUS, Channels.base((oldVal, newVal) -> newVal)),
                entry(DISPATCH_TASKS, Channels.base((oldVal, newVal) -> newVal)),
                entry(SUSPENDED_CHILDREN, Channels.base((oldVal, newVal) -> newVal)),
                entry(CHECKPOINT_ID, Channels.base((oldVal, newVal) -> newVal))
        );
    }

    /**
     * 构建并编译 Supervisor 图。
     *
     * @return 编译后的图
     */
    public CompiledGraph<SupervisorAgentState> buildGraph() {
        try {
            var graph = new StateGraph<>(
                    buildChannels(),
                    SupervisorAgentState::new
            );

            // 所有节点继承 NodeAction，通过 node_async 注册
            graph.addNode(SUPERVISOR_REASON, node_async(reasonNode));
            graph.addNode(DISPATCH_AGENTS, node_async(dispatchNode));
            graph.addNode(AGGREGATE_RESULTS, node_async(aggregateNode));
            graph.addNode(COMPLETE, node_async(completeNode));
            graph.addNode(MAX_ITERATIONS_FALLBACK, node_async(maxIterationsNode));
            graph.addNode(FAILURE, node_async(failureNode));
            graph.addNode(SUSPEND, node_async(suspendNode));

            graph.addEdge(StateGraph.START, SUPERVISOR_REASON);

            // 路由：SupervisorRouter 实现 EdgeAction，通过 edge_async 注册
            graph.addConditionalEdges(
                    SUPERVISOR_REASON,
                    edge_async(router),
                    Map.of(
                            DISPATCH_AGENTS, DISPATCH_AGENTS,
                            COMPLETE, COMPLETE,
                            MAX_ITERATIONS_FALLBACK, MAX_ITERATIONS_FALLBACK,
                            FAILURE, FAILURE
                    )
            );

            // Dispatch 后路由：根据子 Agent 执行状态决定路径
            graph.addConditionalEdges(
                    DISPATCH_AGENTS,
                    edge_async(dispatchRouter),
                    Map.of(
                            AGGREGATE_RESULTS, AGGREGATE_RESULTS,
                            SUSPEND, SUSPEND,
                            FAILURE, FAILURE
                    )
            );

            graph.addEdge(AGGREGATE_RESULTS, SUPERVISOR_REASON);

            graph.addEdge(COMPLETE, StateGraph.END);
            graph.addEdge(MAX_ITERATIONS_FALLBACK, StateGraph.END);
            graph.addEdge(FAILURE, StateGraph.END);
            graph.addEdge(SUSPEND, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile Supervisor graph",
                    e
            );
        }
    }

    /**
     * 构建并编译 Supervisor 恢复图。
     * <p>
     * 恢复图从 DISPATCH_AGENTS 节点开始，不经过 Reason。
     * State 已包含恢复所需的 pendingTasks、dispatchTasks、suspendedChildren 等。
     * Dispatch 节点检测到已有 dispatchTasks 时，只执行 NOT_STARTED 任务。
     * <p>
     * 恢复图与正常图共享所有节点和边，只是入口不同。
     *
     * @return 编译后的恢复图
     */
    public CompiledGraph<SupervisorAgentState> buildResumeGraph() {
        try {
            var graph = new StateGraph<>(
                    buildChannels(),
                    SupervisorAgentState::new
            );

            // 所有节点与正常图相同
            graph.addNode(SUPERVISOR_REASON, node_async(reasonNode));
            graph.addNode(DISPATCH_AGENTS, node_async(dispatchNode));
            graph.addNode(AGGREGATE_RESULTS, node_async(aggregateNode));
            graph.addNode(COMPLETE, node_async(completeNode));
            graph.addNode(MAX_ITERATIONS_FALLBACK, node_async(maxIterationsNode));
            graph.addNode(FAILURE, node_async(failureNode));
            graph.addNode(SUSPEND, node_async(suspendNode));

            // 恢复图入口：START → DISPATCH_AGENTS
            graph.addEdge(StateGraph.START, DISPATCH_AGENTS);

            // Dispatch 后路由
            graph.addConditionalEdges(
                    DISPATCH_AGENTS,
                    edge_async(dispatchRouter),
                    Map.of(
                            AGGREGATE_RESULTS, AGGREGATE_RESULTS,
                            SUSPEND, SUSPEND,
                            FAILURE, FAILURE
                    )
            );

            graph.addEdge(AGGREGATE_RESULTS, SUPERVISOR_REASON);

            // Reason 后路由
            graph.addConditionalEdges(
                    SUPERVISOR_REASON,
                    edge_async(router),
                    Map.of(
                            DISPATCH_AGENTS, DISPATCH_AGENTS,
                            COMPLETE, COMPLETE,
                            MAX_ITERATIONS_FALLBACK, MAX_ITERATIONS_FALLBACK,
                            FAILURE, FAILURE
                    )
            );

            graph.addEdge(COMPLETE, StateGraph.END);
            graph.addEdge(MAX_ITERATIONS_FALLBACK, StateGraph.END);
            graph.addEdge(FAILURE, StateGraph.END);
            graph.addEdge(SUSPEND, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile Supervisor resume graph",
                    e
            );
        }
    }
}
