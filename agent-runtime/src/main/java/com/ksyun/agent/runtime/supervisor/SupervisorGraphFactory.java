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
 * DISPATCH → dispatch_agents → aggregate_results → supervisor_reason
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
    private final SupervisorRouter router;

    public SupervisorGraphFactory(
            SupervisorReasonNode reasonNode,
            SupervisorDispatchNode dispatchNode,
            SupervisorAggregateNode aggregateNode,
            SupervisorCompleteNode completeNode,
            SupervisorMaxIterationsNode maxIterationsNode,
            SupervisorFailureNode failureNode,
            SupervisorRouter router
    ) {
        this.reasonNode = reasonNode;
        this.dispatchNode = dispatchNode;
        this.aggregateNode = aggregateNode;
        this.completeNode = completeNode;
        this.maxIterationsNode = maxIterationsNode;
        this.failureNode = failureNode;
        this.router = router;
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
                entry(RUN_CONTEXT, Channels.base((oldVal, newVal) -> newVal))
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

            graph.addEdge(DISPATCH_AGENTS, AGGREGATE_RESULTS);
            graph.addEdge(AGGREGATE_RESULTS, SUPERVISOR_REASON);

            graph.addEdge(COMPLETE, StateGraph.END);
            graph.addEdge(MAX_ITERATIONS_FALLBACK, StateGraph.END);
            graph.addEdge(FAILURE, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile Supervisor graph",
                    e
            );
        }
    }
}
