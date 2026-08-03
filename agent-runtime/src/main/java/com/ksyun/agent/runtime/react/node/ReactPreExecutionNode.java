package com.ksyun.agent.runtime.react.node;

import com.ksyun.agent.runtime.react.ReactAgentState;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * ReAct 进入首次 Reason 前的通用扩展节点。
 * 默认实现不修改状态；需要审批的节点可替换该实现并返回挂起状态增量。
 */
public interface ReactPreExecutionNode extends NodeAction<ReactAgentState> {
}
