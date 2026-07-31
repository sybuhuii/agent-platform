package com.ksyun.agent.runtime.supervisor;

/**
 * Supervisor 图节点名常量。
 * <p>
 * 集中维护，不得在 GraphFactory 和其他类中散落节点名字面量。
 */
public final class SupervisorNodeNames {

    private SupervisorNodeNames() {
    }

    public static final String SUPERVISOR_REASON = "supervisor_reason";
    public static final String DISPATCH_AGENTS = "dispatch_agents";
    public static final String AGGREGATE_RESULTS = "aggregate_results";
    public static final String COMPLETE = "complete";
    public static final String MAX_ITERATIONS_FALLBACK = "max_iterations_fallback";
    public static final String FAILURE = "failure";
    public static final String SUSPEND = "suspend";
}
