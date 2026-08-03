package com.ksyun.agent.core.approval;

/**
 * 人工审批等待网关。
 *
 * core 只表达业务语义，不暴露 LangChain4j 类型。
 */
public interface HumanApprovalGateway {

    /** 注册一个待人工响应的中断。相同 approvalId 必须幂等。 */
    void interrupt(PendingApproval pendingApproval);

    /**
     * 完成人工响应。实现必须支持在进程重启或 scope 丢失后，
     * 根据 Checkpoint 中的 PendingApproval 重建等待点再完成响应。
     */
    void resume(PendingApproval pendingApproval, ApprovalAction action);

    /** 本次恢复结束后释放第三方框架的临时 scope。 */
    void release(String approvalId);
}