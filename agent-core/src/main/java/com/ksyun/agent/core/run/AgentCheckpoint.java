package com.ksyun.agent.core.run;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 通用 Checkpoint 数据模型。
 * <p>
 * 当前不绑定 LangGraph4j 的具体 State 类型。
 *
 * @param runId     运行 ID
 * @param threadId  线程 ID
 * @param status    运行状态
 * @param state     快照状态数据，不可变
 * @param version   版本号
 * @param updatedAt 更新时间
 */
public record AgentCheckpoint(
        String runId,
        String threadId,
        RunStatus status,
        Map<String, Object> state,
        long version,
        Instant updatedAt
) {

    public AgentCheckpoint {
        state = state == null ? Map.of() : Collections.unmodifiableMap(state);
    }
}
