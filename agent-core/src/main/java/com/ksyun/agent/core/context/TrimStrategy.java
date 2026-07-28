package com.ksyun.agent.core.context;

/**
 * 上下文裁剪策略枚举。
 * <p>
 * 约束：
 * - 不创建 V2、NewXxx 等重复策略
 * - 不使用 NONE 表示"不裁剪"——MAX_MESSAGES(max=Integer.MAX_VALUE) 等价
 */
public enum TrimStrategy {

    /** 按消息数量裁剪，保留最新 N 条消息（不含 System） */
    MAX_MESSAGES,

    /** 按 Token 预算裁剪（预留 Batch2 实现） */
    MAX_TOKENS
}
