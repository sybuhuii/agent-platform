package com.ksyun.agent.core.supervisor;

/**
 * Supervisor 子任务执行状态枚举。
 * <p>
 * 当前批次实际使用 NOT_STARTED、RUNNING、COMPLETED、FAILED、SUSPENDED。
 * CANCELLED 为未来并行任务安全取消预留，本批不实现取消逻辑。
 * <p>
 * 不使用字符串表示状态。
 * 不依赖 Spring、LangGraph4j State、数据库或 API DTO。
 */
public enum SupervisorChildExecutionStatus {

    /** 任务尚未开始执行 */
    NOT_STARTED,

    /** 任务正在执行中 */
    RUNNING,

    /** 任务正常完成 */
    COMPLETED,

    /** 任务执行失败 */
    FAILED,

    /** 任务因人工审批暂停 */
    SUSPENDED,

    /** 任务因其他任务暂停而被取消（未来并行预留） */
    CANCELLED
}
