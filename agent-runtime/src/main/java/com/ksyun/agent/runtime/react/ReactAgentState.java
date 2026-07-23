package com.ksyun.agent.runtime.react;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

/**
 * ReAct 执行状态，继承 LangGraph4j AgentState。
 * <p>
 * 对外访问通过 {@link ReactStateKeys} 提供的类型安全方法，
 * 禁止各节点散落硬编码字符串 key。
 * <p>
 * 不保存 ChatModel、ModelClient、Registry、Gateway 等运行组件。
 * 不把 RunContext 内容拼入 LLM 消息。
 * 状态可在后续 Checkpoint 中序列化，不依赖不可序列化的 Spring 对象。
 */
public class ReactAgentState extends AgentState {

    public ReactAgentState(Map<String, Object> initData) {
        super(initData);
    }
}
