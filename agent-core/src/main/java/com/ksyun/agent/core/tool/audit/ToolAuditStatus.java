package com.ksyun.agent.core.tool.audit;

/**
 * 工具调用审计状态。
 * <p>
 * 状态转换：
 * <pre>
 * STARTED → SUCCEEDED
 * STARTED → FAILED
 * STARTED → SUSPENDED
 * STARTED → EXCEPTION
 * </pre>
 * 不允许反向或相互转换。重复相同终态幂等。
 */
public enum ToolAuditStatus {

    /**
     * 工具调用已开始，尚未完成。
     */
    STARTED,

    /**
     * 工具调用成功完成。
     */
    SUCCEEDED,

    /**
     * 工具调用业务失败（ToolResult.success=false）。
     */
    FAILED,

    /**
     * 工具调用因审批挂起（AgentInterruptSignal）。
     */
    SUSPENDED,

    /**
     * 工具调用因未捕获异常终止。
     */
    EXCEPTION
}
