package com.ksyun.agent.core.run;

import com.ksyun.agent.core.approval.PendingApproval;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;/**
 * 通用 Checkpoint 数据模型，不可变。
 * <p>
 * 当前不绑定 LangGraph4j 的具体 State 类型。
 * <p>
 * 约束：
 * - approval 为 null 表示运行未被中断；非 null 表示因工具审批而中断
 * - approval 非 null 时 status 必须为 INTERRUPTED
 * - approval 为 null 时 status 不得为 INTERRUPTED
 * - version 从 0 开始，每次条件更新递增
 * - 不得在 Checkpoint 中包含密码、credentialHash、sessionId 或 HTTP 对象
 * - state 不得包含可变集合
 *
 * @param runId      运行 ID
 * @param threadId   线程 ID
 * @param status     运行状态
 * @param state      快照状态数据，不可变
 * @param approval   待审批记录，可为 null（表示未被中断）
 * @param version    版本号
 * @param updatedAt  更新时间
 */
public record AgentCheckpoint(
        String runId,
        String threadId,
        RunStatus status,
        Map<String, Object> state,
        PendingApproval approval,
        long version,
        Instant updatedAt
) {

    public AgentCheckpoint {
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(threadId, "threadId must not be null");
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");

        // state 防御性处理
        state = state == null ? Map.of() : Collections.unmodifiableMap(state);

        // approval 与 status 一致性约束
        if (approval != null && status != RunStatus.INTERRUPTED) {
            throw new IllegalArgumentException(
                    "approval must be null when status is not INTERRUPTED");
        }
        if (approval == null && status == RunStatus.INTERRUPTED) {
            throw new IllegalArgumentException(
                    "approval must not be null when status is INTERRUPTED");
        }
    }
}
