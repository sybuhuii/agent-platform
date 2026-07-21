package com.ksyun.agent.core.event;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 简通通用事件模型。
 *
 * @param runId      关联的运行 ID
 * @param type       事件类型
 * @param data       事件数据，不可变
 * @param occurredAt 发生时间
 */
public record AgentEvent(
        String runId,
        String type,
        Map<String, Object> data,
        Instant occurredAt
) {

    public AgentEvent {
        data = data == null ? Map.of() : Collections.unmodifiableMap(data);
    }
}
