package com.ksyun.agent.core.approval;

/**
 * 中断原因枚举。
 * <p>
 * 与 {@link ApprovalStatus} 和 {@link ApprovalDecision} 对应，
 * 但 InterruptReason 描述的是"为什么中断"，不是"审批结果"。
 * <p>
 * 只在本批定义枚举类型和常量，不实现 interrupt/resume 流程。
 */
public enum InterruptReason {

    /**
     * 工具风险等级为 HIGH，需要人工审批后才能执行。
     */
    TOOL_RISK_HIGH,

    /**
     * 工具需要特定权限，当前用户不具备，需要人工确认是否强制执行。
     */
    TOOL_PERMISSION_REQUIRED,

    /**
     * 自定义中断原因，由外部配置或业务逻辑决定。
     */
    CUSTOM
}
