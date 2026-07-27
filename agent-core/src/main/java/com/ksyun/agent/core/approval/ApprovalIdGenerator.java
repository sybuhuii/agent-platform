package com.ksyun.agent.core.approval;

/**
 * 审批 ID 生成器接口。
 * <p>
 * 位于 agent-core，实现位于 agent-infrastructure。
 * 不包含 userId、sessionId 和工具参数。
 */
@FunctionalInterface
public interface ApprovalIdGenerator {

    /**
     * 生成唯一审批 ID。
     *
     * @return 审批 ID
     */
    String generate();
}
