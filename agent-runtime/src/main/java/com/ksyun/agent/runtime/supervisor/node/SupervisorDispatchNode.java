package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Supervisor Dispatch 节点契约。
 * <p>
 * 根据 pendingTasks 分派子 Agent 执行。
 * 继承 LangGraph4j {@link NodeAction}，由 GraphFactory 通过 node_async 注册。
 * <p>
 * 本批只定义契约，不实现真实业务节点。
 */
public interface SupervisorDispatchNode extends NodeAction<SupervisorAgentState> {
}
