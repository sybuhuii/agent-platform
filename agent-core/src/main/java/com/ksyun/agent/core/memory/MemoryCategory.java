package com.ksyun.agent.core.memory;

/**
 * 长期记忆类别。
 * <p>
 * 所有类别数据仍按 userId 隔离，category 不决定数据权限。
 * 不得包含 SHORT_TERM 或 CHECKPOINT。
 * 不得包含模型供应商信息。
 */
public enum MemoryCategory {

    /** 较稳定的用户身份或背景信息 */
    PROFILE,

    /** 用户明确表达的偏好 */
    PREFERENCE,

    /** 与用户相关、可长期复用的事实 */
    FACT,

    /** 用户希望 Agent 长期遵守的个人规则 */
    RULE
}
