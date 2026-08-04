package com.ksyun.agent.infrastructure.store;

import com.ksyun.agent.core.tool.audit.ToolAuditIdGenerator;

import java.util.UUID;

/**
 * 基于 UUID 的工具审计 ID 生成器。
 * <p>
 * 无状态、线程安全。不包含 userId、sessionId 和工具参数。
 */
public class UuidToolAuditIdGenerator implements ToolAuditIdGenerator {

    @Override
    public String generate() {
        return "aud-" + UUID.randomUUID();
    }
}
