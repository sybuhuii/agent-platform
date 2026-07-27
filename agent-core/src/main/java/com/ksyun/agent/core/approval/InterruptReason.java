package com.ksyun.agent.core.approval;

/**
 * 中断原因枚举。
 * <p>
 * 与 {@link ApprovalStatus} 和 {@link ApprovalDecision} 对应，
 * 但 InterruptReason 描述的是"为什么中断"，不是"审批结果"。
 * <p>
 * 注意：ACL 拒绝必须直接拒绝，不能创建审批。
 * TOOL_PERMISSION_REQUIRED 不用于"无权限工具可通过人工审批强制执行"。
 */
public enum InterruptReason {

    /**
     * 工具风险等级为 HIGH，需要人工审批后才能执行。
     */
    TOOL_RISK_HIGH,

    /**
     * 预留，不代表"无权限可通过审批强制执行"。
     * ACL 拒绝必须直接拒绝，不创建审批。
     */
    TOOL_PERMISSION_REQUIRED,

    /**
     * 自定义中断原因，由外部配置或业务逻辑决定。
     */
    CUSTOM
}
