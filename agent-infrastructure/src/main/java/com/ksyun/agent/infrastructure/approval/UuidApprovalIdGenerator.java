package com.ksyun.agent.infrastructure.approval;

import com.ksyun.agent.core.approval.ApprovalIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的审批 ID 生成器。
 * <p>
 * 无状态、线程安全。不包含 userId、sessionId 和工具参数。
 */
public class UuidApprovalIdGenerator implements ApprovalIdGenerator {

    @Override
    public String generate() {
        return "apr-" + UUID.randomUUID();
    }
}
