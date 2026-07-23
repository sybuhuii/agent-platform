package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Supervisor 失败节点契约。
 * <p>
 * 状态或节点执行失败，构建错误 AgentResult 并结束。
 * 继承 LangGraph4j {@link NodeAction}，由 GraphFactory 通过 node_async 注册。
 * <p>
 * 本批只定义契约，不实现真实业务节点。
 */
public interface SupervisorFailureNode extends NodeAction<SupervisorAgentState> {
}
