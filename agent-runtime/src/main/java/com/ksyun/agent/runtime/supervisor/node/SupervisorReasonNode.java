package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import org.bsc.langgraph4j.action.NodeAction;

import java.util.Map;

/**
 * Supervisor Reason 节点契约。
 * <p>
 * Supervisor 调用模型，决定分派子 Agent 或完成任务。
 * 继承 LangGraph4j {@link NodeAction}，由 GraphFactory 通过 node_async 注册。
 * <p>
 * 本批只定义契约，不实现真实业务节点。
 * 下一批具体实现通过构造器接收依赖。
 */
public interface SupervisorReasonNode extends NodeAction<SupervisorAgentState> {
}
