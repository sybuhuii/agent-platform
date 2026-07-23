package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * ReAct 失败节点契约。
 * <p>
 * 状态或节点执行失败，构建错误 AgentResult 并结束。
 * <p>
 * 继承 LangGraph4j {@link NodeAction}：Failure 是纯结果构造，
 * 无真正异步操作，由 GraphFactory 通过 node_async 注册。
 */
public interface ReactFailureNode extends NodeAction<ReactAgentState> {
}
