package com.ksyun.agent.runtime.react;

/**
 * ReAct 图节点名常量。
 * <p>
 * 集中维护，不得把节点名字符串散落在多个类中。
 */
public final class ReactNodeNames {

    private ReactNodeNames() {
    }

    public static final String REASON = "reason";
    public static final String EXECUTE_TOOLS = "execute_tools";
    public static final String OBSERVE = "observe";
    public static final String COMPLETE = "complete";
    public static final String MAX_ITERATIONS_FALLBACK = "max_iterations_fallback";
    public static final String FAILURE = "failure";
    public static final String SUSPEND = "suspend";
}
