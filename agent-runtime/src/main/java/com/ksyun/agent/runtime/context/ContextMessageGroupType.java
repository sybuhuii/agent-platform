package com.ksyun.agent.runtime.context;

/**
 * 上下文消息分组类型。
 */
public enum ContextMessageGroupType {

    /** 单条 System 消息 */
    SYSTEM,

    /** 单条普通用户或助手消息 */
    NORMAL,

    /** 工具交互原子组：含 ToolCall 的 Assistant + 全部对应 ToolAgentMessage */
    TOOL_INTERACTION,

    /** 框架生成的摘要消息，包含且只包含一条 SummaryAgentMessage */
    SUMMARY
}
