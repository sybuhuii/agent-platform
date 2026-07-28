package com.ksyun.agent.core.context;

import com.ksyun.agent.core.message.AgentMessage;

/**
 * 上下文裁剪器 SPI。
 * <p>
 * 位于 agent-core，框架无关。
 * <p>
 * 约束：
 * - 不依赖 Spring AI
 * - 不依赖 LangGraph4j
 * - 不依赖具体模型供应商
 * - 不执行模型
 * - 不保存状态
 * - 不修改输入消息集合
 * - 不得返回 null
 * - 允许后续增加 Token 和摘要实现
 * - 不要在接口中加入大量当前未使用方法
 */
public interface ContextTrimmer {

    /**
     * 根据请求裁剪消息列表。
     *
     * @param request 裁剪请求
     * @return 裁剪结果，不为 null
     */
    ContextTrimResult trim(ContextTrimRequest request);
}
