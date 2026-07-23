package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * ReAct Complete 节点契约。
 * <p>
 * 模型已产生最终回答，构建 AgentResult 并结束。
 * <p>
 * 继承 LangGraph4j {@link NodeAction}：Complete 是纯结果构造，
 * 无真正异步操作，由 GraphFactory 通过 node_async 注册。
 */
public interface ReactCompleteNode extends NodeAction<ReactAgentState> {
}
