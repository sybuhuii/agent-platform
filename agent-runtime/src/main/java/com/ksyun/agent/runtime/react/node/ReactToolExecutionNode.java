package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * ReAct 工具执行节点契约。
 * <p>
 * 执行 pendingToolCalls 中的工具调用。
 * 后续实现通过构造器接收 ToolInvocationGateway 等依赖。
 * <p>
 * 继承 LangGraph4j {@link NodeAction}：工具执行通过同步
 * ToolInvocationGateway 执行，由 GraphFactory 通过 node_async 注册。
 */
public interface ReactToolExecutionNode extends NodeAction<ReactAgentState> {
}
