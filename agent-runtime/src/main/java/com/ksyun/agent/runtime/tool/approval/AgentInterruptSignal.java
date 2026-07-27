package com.ksyun.agent.runtime.tool.approval;

import com.ksyun.agent.core.approval.PendingApproval;

/**
 * 统一中断信号。
 * <p>
 * 它是控制信号，不是普通工具失败。
 * <p>
 * 约束：
 * - message 只能是安全说明
 * - 不携带 ReactAgentState、Session、Spring、Servlet、密码和密钥
 * - 只有 ToolApprovalInterceptor 创建
 * - 普通异常不能伪装为中断
 * <p>
 * 传播路径：
 * ToolApprovalInterceptor 抛出
 * → ToolExceptionHandlingInterceptor 捕获后原样重新抛出
 * → ToolAuditInterceptor 捕获后记录安全状态并原样抛出
 * → DefaultReactToolExecutionNode 捕获并保存 Checkpoint
 */
public class AgentInterruptSignal extends RuntimeException {

    private final PendingApproval pendingApproval;

    public AgentInterruptSignal(PendingApproval pendingApproval) {
        super("Agent execution suspended: tool requires manual approval");
        if (pendingApproval == null) {
            throw new IllegalArgumentException("pendingApproval must not be null");
        }
        this.pendingApproval = pendingApproval;
    }

    public PendingApproval getPendingApproval() {
        return pendingApproval;
    }
}
