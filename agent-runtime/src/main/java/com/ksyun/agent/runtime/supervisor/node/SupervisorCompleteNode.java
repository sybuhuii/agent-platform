package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Supervisor Complete 节点契约。
 * <p>
 * Supervisor 已产生最终回答，构建 AgentResult 并结束。
 * 继承 LangGraph4j {@link NodeAction}，由 GraphFactory 通过 node_async 注册。
 * <p>
 * 本批只定义契约，不实现真实业务节点。
 */
public interface SupervisorCompleteNode extends NodeAction<SupervisorAgentState> {
}
