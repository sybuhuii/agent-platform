package com.ksyun.agent.core.context;

/**
 * 上下文裁剪诊断枚举。
 * <p>
 * 约束：
 * - 不存储完整消息内容
 * - 不存储工具参数
 * - 不存储权限和身份
 * - 仅用于日志、调试和后续页面展示
 * - 不得根据 diagnostics 改变授权行为
 */
public enum ContextTrimDiagnostic {

    /** System 消息已永久保留 */
    SYSTEM_MESSAGES_PRESERVED,

    /** 非系统消息已裁剪到目标数量 */
    RECENT_MESSAGES_TRIMMED,

    /** 工具交互组已完整保留 */
    TOOL_GROUP_PRESERVED,

    /** 因原子组完整性需要，保留消息数超过目标 */
    ATOMIC_GROUP_OVERSHOOT,

    /** 最新用户输入已保护保留 */
    LATEST_USER_MESSAGE_PRESERVED,

    /** 消息数量未超过限制，无需裁剪 */
    NO_TRIMMING_REQUIRED,

    // --- 第七阶段第2批新增诊断 ---

    /** 消息数裁剪已执行 */
    MESSAGE_COUNT_TRIM_APPLIED,

    /** Token 裁剪已执行 */
    TOKEN_TRIM_APPLIED,

    /** Token 预算未超出 */
    TOKEN_BUDGET_NOT_EXCEEDED,

    /** System 消息 Token 已超出预算 */
    SYSTEM_TOKEN_BUDGET_EXCEEDED,

    /** 强制上下文（System + 最新用户）过大 */
    MANDATORY_CONTEXT_TOO_LARGE,

    /** 工具原子组因 Token 预算被跳过 */
    TOOL_GROUP_SKIPPED_FOR_TOKEN_BUDGET,

    /** 旧消息因 Token 预算被删除 */
    OLD_MESSAGES_REMOVED_FOR_TOKEN_BUDGET,

    /** 最终 Token 预算验证通过 */
    FINAL_TOKEN_BUDGET_VERIFIED,

    /** Token 预算充足，无需 Token 裁剪 */
    NO_TOKEN_TRIMMING_REQUIRED,

    // --- 第七阶段第3批新增诊断 ---

    /** 摘要已触发 */
    SUMMARY_TRIGGERED,

    /** 摘要已成功应用 */
    SUMMARY_APPLIED,

    /** 摘要功能已关闭 */
    SUMMARY_SKIPPED_DISABLED,

    /** Token 使用率未达到摘要触发阈值 */
    SUMMARY_SKIPPED_BELOW_THRESHOLD,

    /** 没有可摘要的旧消息 */
    SUMMARY_SKIPPED_NO_SOURCE,

    /** 可摘要源 Token 数太少 */
    SUMMARY_SKIPPED_SOURCE_TOO_SMALL,

    /** 摘要器不可用（无模型配置） */
    SUMMARY_UNAVAILABLE,

    /** 摘要失败，降级为普通裁剪 */
    SUMMARY_FAILED_FALLBACK_TO_TRIMMING,

    /** 旧摘要已被新摘要替换 */
    EXISTING_SUMMARY_REPLACED
}
