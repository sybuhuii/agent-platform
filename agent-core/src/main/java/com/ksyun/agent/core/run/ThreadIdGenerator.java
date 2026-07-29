package com.ksyun.agent.core.run;

/**
 * 线程 ID 生成器。
 * <p>
 * 使用 UUID 或等价不可预测 ID。
 * 不得使用递增整数、当前时间戳作为唯一 ID。
 * 不得包含 userId、sessionId、agentName。
 * 不得访问 CheckpointStore。
 * 不得生成空字符串。
 * 允许自定义 Bean 替换默认实现。
 */
@FunctionalInterface
public interface ThreadIdGenerator {

    /**
     * 生成唯一的线程 ID。
     *
     * @return 线程 ID
     */
    String generate();
}
