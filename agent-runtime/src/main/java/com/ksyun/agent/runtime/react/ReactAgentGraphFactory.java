package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.runtime.react.node.*;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactNodeNames.*;
import static com.ksyun.agent.runtime.react.ReactStateKeys.*;
import static java.util.Map.entry;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * ReAct 图工厂。
 * <p>
 * 根据节点实现构建通用 ReAct StateGraph。
 * 通过构造器接收各节点，不从 Spring 容器主动查找。
 * 图工厂本身不保存某次运行的可变 State。
 * <p>
 * 图结构：
 * START → reason → 条件路由
 * EXECUTE_TOOLS → execute_tools → observe → reason
 * COMPLETE → complete → END
 * MAX_ITERATIONS → max_iterations_fallback → END
 * FAIL → failure → END
 */
public class ReactAgentGraphFactory {

    private final ReactReasonNode reasonNode;
    private final ReactToolExecutionNode toolExecutionNode;
    private final ReactObserveNode observeNode;
    private final ReactCompleteNode completeNode;
    private final ReactMaxIterationsNode maxIterationsNode;
    private final ReactFailureNode failureNode;
    private final ReactRouter router;

    public ReactAgentGraphFactory(
            ReactReasonNode reasonNode,
            ReactToolExecutionNode toolExecutionNode,
            ReactObserveNode observeNode,
            ReactCompleteNode completeNode,
            ReactMaxIterationsNode maxIterationsNode,
            ReactFailureNode failureNode,
            ReactRouter router
    ) {
        this.reasonNode = reasonNode;
        this.toolExecutionNode = toolExecutionNode;
        this.observeNode = observeNode;
        this.completeNode = completeNode;
        this.maxIterationsNode = maxIterationsNode;
        this.failureNode = failureNode;
        this.router = router;
    }

    /**
     * 构建 Channel 定义，配置各状态字段的合并语义。
     * <p>
     * 合并语义说明：
     * - messages: AppenderChannel，追加，不会覆盖历史
     * - toolTraces: AppenderChannel，追加
     * - pendingToolCalls: 覆盖（last-write-wins）
     * - latestToolResults: 覆盖
     * - iteration: 覆盖，节点明确写入新值，不配置 Integer::sum
     * - finalResult: 覆盖
     * - stopReason: 覆盖
     * - failureMessage: 覆盖
     * - failureErrorCode: 覆盖
     * - agentDefinition, task, runContext: 覆盖（初始化后不再变更）
     */
    private Map<String, Channel<?>> buildChannels() {
        return Map.ofEntries(
                entry(MESSAGES, Channels.<Object>appender(ArrayList::new)),
                entry(TOOL_TRACES, Channels.<Object>appender(ArrayList::new)),

                entry(PENDING_TOOL_CALLS, Channels.base(ArrayList::new)),
                entry(LATEST_TOOL_RESULTS, Channels.base(ArrayList::new)),

                entry(ITERATION, Channels.base(() -> 0)),

                // Channels without default providers — initial values are always
                // supplied explicitly by DefaultReactAgentEngine.execute().
                // Using Channels.base(reducer) so getDefault() returns Optional.empty(),
                // preventing Collectors.toMap NPE in AgentStateFactory.initialDataFromSchema().
                entry(FINAL_RESULT, Channels.base((oldVal, newVal) -> newVal)),
                entry(STOP_REASON, Channels.base((oldVal, newVal) -> newVal)),
                entry(FAILURE_MESSAGE, Channels.base((oldVal, newVal) -> newVal)),
                entry(FAILURE_ERROR_CODE, Channels.base((oldVal, newVal) -> newVal)),

                entry(AGENT_DEFINITION, Channels.base((oldVal, newVal) -> newVal)),
                entry(TASK, Channels.base((oldVal, newVal) -> newVal)),
                entry(RUN_CONTEXT, Channels.base((oldVal, newVal) -> newVal))
        );
    }

    /**
     * 构建并编译 ReAct 图。
     *
     * @return 编译后的图
     */
    public CompiledGraph<ReactAgentState> buildGraph() {
        try {
            var graph = new StateGraph<>(
                    buildChannels(),
                    ReactAgentState::new
            );

            // 所有节点继承 NodeAction，通过 node_async 注册
            graph.addNode(REASON, node_async(reasonNode));
            graph.addNode(EXECUTE_TOOLS, node_async(toolExecutionNode));
            graph.addNode(OBSERVE, node_async(observeNode));
            graph.addNode(COMPLETE, node_async(completeNode));
            graph.addNode(MAX_ITERATIONS_FALLBACK, node_async(maxIterationsNode));
            graph.addNode(FAILURE, node_async(failureNode));

            graph.addEdge(StateGraph.START, REASON);

            // 路由：ReactRouter 实现 EdgeAction，通过 edge_async 注册
            graph.addConditionalEdges(
                    REASON,
                    edge_async(router),
                    Map.of(
                            EXECUTE_TOOLS, EXECUTE_TOOLS,
                            COMPLETE, COMPLETE,
                            MAX_ITERATIONS_FALLBACK, MAX_ITERATIONS_FALLBACK,
                            FAILURE, FAILURE
                    )
            );

            graph.addEdge(EXECUTE_TOOLS, OBSERVE);
            graph.addEdge(OBSERVE, REASON);

            graph.addEdge(COMPLETE, StateGraph.END);
            graph.addEdge(MAX_ITERATIONS_FALLBACK, StateGraph.END);
            graph.addEdge(FAILURE, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile ReAct graph",
                    e
            );
        }
    }
}
