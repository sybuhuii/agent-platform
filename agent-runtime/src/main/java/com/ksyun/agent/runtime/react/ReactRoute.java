package com.ksyun.agent.runtime.react;

/**
 * ReAct 路由结果枚举。
 */
public enum ReactRoute {

    /** 模型返回一个或多个 ToolCall，需要执行工具 */
    EXECUTE_TOOLS,

    /** 模型不再请求工具，产生最终回答 */
    COMPLETE,

    /** 达到 AgentDefinition.maxIterations 上限 */
    MAX_ITERATIONS,

    /** 状态或节点执行失败 */
    FAIL
}
