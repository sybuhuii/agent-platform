package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * ReAct Reason 节点契约。
 * <p>
 * 调用模型，决定是否需要工具调用。
 * 后续实现通过构造器接收 ModelInvocationGateway 等依赖。
 * <p>
 * 继承 LangGraph4j {@link NodeAction}：Reason 节点内部通过同步
 * ModelInvocationGateway 调用模型，由 GraphFactory 通过 node_async 注册。
 */
public interface ReactReasonNode extends NodeAction<ReactAgentState> {
}
