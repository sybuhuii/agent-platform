package com.ksyun.agent.runtime.supervisor;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

/**
 * Supervisor 执行状态，继承 LangGraph4j AgentState。
 * <p>
 * 状态值存储在 AgentState 父类 Map 中。
 * 对外访问通过 {@link SupervisorStateKeys} 提供的类型安全方法。
 * 禁止各节点散落硬编码字符串 key。
 * 不得保存 ChatModel、ModelClient、Registry、Gateway、SpringBean、CompiledGraph 或异常对象。
 */
public class SupervisorAgentState extends AgentState {

    public SupervisorAgentState(Map<String, Object> initData) {
        super(initData);
    }
}
