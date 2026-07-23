package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * ReAct Observe 节点契约。
 * <p>
 * 将工具执行结果转换为观察消息，准备下一轮 Reason。
 * <p>
 * 继承 LangGraph4j {@link NodeAction}：Observe 是纯状态转换，
 * 无真正异步操作，由 GraphFactory 通过 node_async 注册。
 */
public interface ReactObserveNode extends NodeAction<ReactAgentState> {
}
