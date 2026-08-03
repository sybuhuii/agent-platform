package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.runtime.react.node.*;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Map;

import static com.ksyun.agent.runtime.react.ReactNodeNames.*;
import static com.ksyun.agent.runtime.react.ReactStateKeys.*;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * ReAct Agent 图工厂。
 * <p>
 * 只负责构图，不负责执行。
 * 图结构：START -> reason -> [条件路由]
 *   -> EXECUTE_TOOLS -> [条件路由]
 *     -> OBSERVE -> reason (循环)
 *     -> SUSPEND -> END
 *     -> FAILURE -> END
 *   -> COMPLETE -> END
 *   -> MAX_ITERATIONS_FALLBACK -> END
 *   -> SUSPEND -> END
 *   -> FAILURE -> END
 */
public class ReactAgentGraphFactory {

    private final ReactReasonNode reasonNode;
    private final ReactPreExecutionNode preExecutionNode;
    private final ReactToolExecutionNode toolExecutionNode;
    private final ReactObserveNode observeNode;
    private final ReactCompleteNode completeNode;
    private final ReactMaxIterationsNode maxIterationsNode;
    private final ReactFailureNode failureNode;
    private final NodeAction<ReactAgentState> suspendNode;
    private final ReactRouter router;
    private final ReactToolExecutionRouter toolExecutionRouter;
    private final ReactPreExecutionRouter preExecutionRouter;

    public ReactAgentGraphFactory(
            ReactPreExecutionNode preExecutionNode,
            ReactReasonNode reasonNode,
            ReactToolExecutionNode toolExecutionNode,
            ReactObserveNode observeNode,
            ReactCompleteNode completeNode,
            ReactMaxIterationsNode maxIterationsNode,
            ReactFailureNode failureNode,
            NodeAction<ReactAgentState> suspendNode,
            ReactRouter router,
            ReactToolExecutionRouter toolExecutionRouter,
            ReactPreExecutionRouter preExecutionRouter
    ) {
        this.preExecutionNode = preExecutionNode;
        this.reasonNode = reasonNode;
        this.toolExecutionNode = toolExecutionNode;
        this.observeNode = observeNode;
        this.completeNode = completeNode;
        this.maxIterationsNode = maxIterationsNode;
        this.failureNode = failureNode;
        this.suspendNode = suspendNode;
        this.router = router;
        this.toolExecutionRouter = toolExecutionRouter;
        this.preExecutionRouter = preExecutionRouter;
    }

    public CompiledGraph<ReactAgentState> buildGraph() {
        try {
            StateGraph<ReactAgentState> graph = new StateGraph<>(buildChannels(), ReactAgentState::new);

            // 注册节点
            graph.addNode(PRE_EXECUTION, node_async(preExecutionNode));
            graph.addNode(REASON, node_async(reasonNode));
            graph.addNode(EXECUTE_TOOLS, node_async(toolExecutionNode));
            graph.addNode(OBSERVE, node_async(observeNode));
            graph.addNode(COMPLETE, node_async(completeNode));
            graph.addNode(MAX_ITERATIONS_FALLBACK, node_async(maxIterationsNode));
            graph.addNode(FAILURE, node_async(failureNode));
            graph.addNode(SUSPEND, node_async(suspendNode));

            // 入口边
            graph.addEdge(StateGraph.START, PRE_EXECUTION);
            graph.addConditionalEdges(PRE_EXECUTION, edge_async(preExecutionRouter), Map.of(
                    REASON, REASON,
                    SUSPEND, SUSPEND,
                    FAILURE, FAILURE
            ));

            // 条件路由：reason 节点后根据状态决定下一步
            graph.addConditionalEdges(REASON, edge_async(router), Map.of(
                    EXECUTE_TOOLS, EXECUTE_TOOLS,
                    COMPLETE, COMPLETE,
                    MAX_ITERATIONS_FALLBACK, MAX_ITERATIONS_FALLBACK,
                    FAILURE, FAILURE,
                    SUSPEND, SUSPEND
            ));

            // 条件路由：execute_tools 后根据执行结果决定下一步
            // 替代原来的固定边 EXECUTE_TOOLS -> OBSERVE
            graph.addConditionalEdges(EXECUTE_TOOLS, edge_async(toolExecutionRouter), Map.of(
                    OBSERVE, OBSERVE,
                    SUSPEND, SUSPEND,
                    FAILURE, FAILURE
            ));

            // 循环边：observe -> reason
            graph.addEdge(OBSERVE, REASON);

            // 终止边
            graph.addEdge(COMPLETE, StateGraph.END);
            graph.addEdge(MAX_ITERATIONS_FALLBACK, StateGraph.END);
            graph.addEdge(FAILURE, StateGraph.END);
            graph.addEdge(SUSPEND, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile ReAct graph",
                    e
            );
        }
    }

    private Map<String, Channel<?>> buildChannels() {
        return Map.ofEntries(
                Map.entry(AGENT_DEFINITION, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(TASK, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(RUN_CONTEXT, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(MESSAGES, Channels.appender(ArrayList::new)),
                Map.entry(PENDING_TOOL_CALLS, Channels.base(ArrayList::new)),
                Map.entry(LATEST_TOOL_RESULTS, Channels.base(ArrayList::new)),
                Map.entry(TOOL_TRACES, Channels.appender(ArrayList::new)),
                Map.entry(ITERATION, Channels.base(() -> 0)),
                Map.entry(FINAL_RESULT, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(STOP_REASON, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(FAILURE_MESSAGE, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(FAILURE_ERROR_CODE, Channels.base((oldVal, newVal) -> newVal)),
                // Phase6 Batch2 新增 Channel
                Map.entry(TOOL_EXECUTION_CURSOR, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(TOOL_EXECUTION_BUFFER, Channels.base(ArrayList::new)),
                Map.entry(PENDING_APPROVAL, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(CHECKPOINT_ID, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(RUN_STATUS, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(NODE_RESUME_HANDLER_KEY, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(NODE_RESUME_DATA, Channels.base((oldVal, newVal) -> newVal)),
                // Phase7 Batch4 上下文窗口 Channel（覆盖语义）
                Map.entry(CONTEXT_WINDOW_SNAPSHOT, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(LATEST_CONTEXT_TRACE, Channels.base((oldVal, newVal) -> newVal)),
                Map.entry(LATEST_MEMORY_CONTEXT_TRACE, Channels.base((oldVal, newVal) -> newVal))
        );
    }

    /**
     * 编译恢复专用图。
     * <p>
     * START 直连 execute_tools，不经过 reason。
     * execute_tools 后条件路由与主图相同：OBSERVE / SUSPEND / FAILURE。
     * OBSERVE 后进入 reason（恢复正常循环）。
     * <p>
     * 不用 LangGraph4j CheckpointSaver。使用 agent-core CheckpointStore。
     * 恢复使用原 runId 和 threadId。恢复不重新进入首次 Reason。
     *
     * @return 恢复专用 CompiledGraph
     */
    public CompiledGraph<ReactAgentState> compileForResume() {
        try {
            StateGraph<ReactAgentState> graph = new StateGraph<>(buildChannels(), ReactAgentState::new);

            // 注册恢复需要的节点
            graph.addNode(EXECUTE_TOOLS, node_async(toolExecutionNode));
            graph.addNode(OBSERVE, node_async(observeNode));
            graph.addNode(REASON, node_async(reasonNode));
            graph.addNode(COMPLETE, node_async(completeNode));
            graph.addNode(MAX_ITERATIONS_FALLBACK, node_async(maxIterationsNode));
            graph.addNode(FAILURE, node_async(failureNode));
            graph.addNode(SUSPEND, node_async(suspendNode));

            // 恢复入口：START 直连 execute_tools
            graph.addEdge(StateGraph.START, EXECUTE_TOOLS);

            // execute_tools 后条件路由
            graph.addConditionalEdges(EXECUTE_TOOLS, edge_async(toolExecutionRouter), Map.of(
                    OBSERVE, OBSERVE,
                    SUSPEND, SUSPEND,
                    FAILURE, FAILURE
            ));

            // 循环边：observe -> reason
            graph.addEdge(OBSERVE, REASON);

            // reason 后条件路由
            graph.addConditionalEdges(REASON, edge_async(router), Map.of(
                    EXECUTE_TOOLS, EXECUTE_TOOLS,
                    COMPLETE, COMPLETE,
                    MAX_ITERATIONS_FALLBACK, MAX_ITERATIONS_FALLBACK,
                    FAILURE, FAILURE,
                    SUSPEND, SUSPEND
            ));

            // 终止边
            graph.addEdge(COMPLETE, StateGraph.END);
            graph.addEdge(MAX_ITERATIONS_FALLBACK, StateGraph.END);
            graph.addEdge(FAILURE, StateGraph.END);
            graph.addEdge(SUSPEND, StateGraph.END);

            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile ReAct resume graph",
                    e
            );
        }
    }

    /**
     * 编译节点中断的专用续跑图。恢复动作由白名单 Handler 创建，
     * 图工厂只负责注册原中断节点以及 START/END 边。
     */
    public CompiledGraph<ReactAgentState> compileForNodeResume(
            String nodeName,
            NodeAction<ReactAgentState> resumeAction) {
        if (nodeName == null || nodeName.isBlank() || resumeAction == null) {
            throw new IllegalArgumentException("nodeName and resumeAction must be provided");
        }
        try {
            StateGraph<ReactAgentState> graph = new StateGraph<>(buildChannels(), ReactAgentState::new);
            graph.addNode(nodeName, node_async(resumeAction));
            graph.addEdge(StateGraph.START, nodeName);
            graph.addEdge(nodeName, StateGraph.END);
            return graph.compile();
        } catch (GraphStateException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to compile node resume graph",
                    e);
        }
    }
}
