package com.ksyun.agent.core.tool.audit;

/**
 * 工具审计 ID 生成器 SPI。
 * <p>
 * 位于 agent-core，实现位于 agent-infrastructure。
 * 不包含 userId、sessionId 和工具参数。
 */
@FunctionalInterface
public interface ToolAuditIdGenerator {

    /**
     * 生成唯一审计 ID。
     *
     * @return 审计 ID
     */
    String generate();
}
