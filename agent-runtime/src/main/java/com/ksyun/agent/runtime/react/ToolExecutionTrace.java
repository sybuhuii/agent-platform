package com.ksyun.agent.runtime.react;

import java.io.Serializable;
import java.time.Instant;

/**
 * 工具执行轨迹，不可变。
 * <p>
 * 不保存完整工具参数、完整返回内容或异常对象。
 * 字段适合后续审计、前端轨迹和 Checkpoint。
 *
 * @param toolCallId     工具调用 ID
 * @param toolName       工具名称
 * @param success        是否成功
 * @param errorCode      错误码，成功时为 null
 * @param durationMillis 执行耗时毫秒
 * @param startedAt      开始时间
 * @param finishedAt     结束时间
 */
public record ToolExecutionTrace(
        String toolCallId,
        String toolName,
        boolean success,
        String errorCode,
        long durationMillis,
        Instant startedAt,
        Instant finishedAt
) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
}
