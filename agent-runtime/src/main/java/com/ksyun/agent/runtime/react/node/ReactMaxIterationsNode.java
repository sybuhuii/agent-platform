package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * ReAct 达到最大迭代次数回退节点契约。
 * <p>
 * 构建 MAX_ITERATIONS_REACHED 的 AgentResult 并结束。
 * <p>
 * 继承 LangGraph4j {@link NodeAction}：MaxIterations 是纯结果构造，
 * 无真正异步操作，由 GraphFactory 通过 node_async 注册。
 */
public interface ReactMaxIterationsNode extends NodeAction<ReactAgentState> {
}
