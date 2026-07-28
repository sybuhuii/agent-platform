package com.ksyun.agent.core.context;

/**
 * 上下文摘要器接口。
 * <p>
 * 接口只负责把已选择的源消息压缩成摘要。
 * <p>
 * 约束：
 * - 位于稳定模块
 * - 不依赖 Spring
 * - 不依赖 Spring AI 类型
 * - 不依赖 LangGraph4j
 * - 不返回 null
 * - 不保存跨请求状态
 * - 不把摘要策略和消息选择混入该接口
 */
public interface ContextSummarizer {

    /**
     * 将已选择的源消息压缩成摘要。
     *
     * @param request 摘要请求
     * @return 摘要结果，不为 null
     */
    ContextSummaryResult summarize(ContextSummaryRequest request);
}
