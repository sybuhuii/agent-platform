package com.ksyun.agent.core.approval;

/**
 * 审批动作，客户端只能提交 APPROVE 或 REJECT。
 * <p>
 * 不与 ApprovalStatus 重复表达 PENDING。
 * 不得提供 AUTO_APPROVE、ADMIN_OVERRIDE 或模型审批动作。
 */
public enum ApprovalAction {

    /** 批准执行 */
    APPROVE,

    /** 拒绝执行 */
    REJECT
}
