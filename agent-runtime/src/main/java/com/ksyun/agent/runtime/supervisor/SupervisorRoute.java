package com.ksyun.agent.runtime.supervisor;

/**
 * Supervisor 路由结果枚举。
 */
public enum SupervisorRoute {

    /** 分派子 Agent */
    DISPATCH,

    /** 正常完成 */
    COMPLETE,

    /** 达到最大迭代次数 */
    MAX_ITERATIONS,

    /** 失败 */
    FAIL
}
