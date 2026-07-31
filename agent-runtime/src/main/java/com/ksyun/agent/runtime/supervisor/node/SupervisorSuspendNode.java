package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * Supervisor Suspend 节点契约。
 * <p>
 * 当子 Agent 返回 SUSPENDED 时，Supervisor 进入暂停终态。
 * 继承 LangGraph4j {@link NodeAction}，由 GraphFactory 通过 node_async 注册。
 */
public interface SupervisorSuspendNode extends NodeAction<SupervisorAgentState> {
}
