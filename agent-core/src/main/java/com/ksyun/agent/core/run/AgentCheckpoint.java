package com.ksyun.agent.core.run;

import com.ksyun.agent.core.approval.PendingApproval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 通用 Checkpoint 数据模型，不可变。
 * <p>
 * 恢复语义：本框架采用"节点重跑"恢复——中断发生在危险操作真正执行之前，
 * 恢复后从保存的节点重新执行，不是从 Java 方法中间继续。
 * <p>
 * 约束：
 * - SUSPENDED 必须有 pendingApproval
 * - COMPLETED 不得继续恢复
 * - version >= 0
 * - createdAt、updatedAt 非空
 * - stateData 不得为空，必须是真正的不可变快照
 * - 不能只使用 Collections.unmodifiableMap(originalMap)，必须先复制
 * - 对状态中的已知 List、Set、Map 做防御性复制
 * - 不保存 CompiledGraph、Gateway、Registry、Spring Bean、模型客户端或异常
 * - 不得在 Checkpoint 中包含密码、credentialHash、sessionId 或 HTTP 对象
 * - 持久化安全：Checkpoint 顶层和 payload 都不得保存 sessionId、roles、permissions
 *   或完整 UserSession；恢复时的当前身份由调用方从已验证 UserSession 重建
 * - purpose 不可为空，区分 HITL_RECOVERY 和 THREAD_MEMORY
 * - HITL_RECOVERY 用于中断恢复，THREAD_MEMORY 用于线程短期状态
 * - 现有 HITL 构造调用必须补充 HITL_RECOVERY
 * - 兼容构造器只能安全地默认 HITL_RECOVERY
 * - 不得让普通调用在未指定 purpose 时默认 THREAD_MEMORY
 *
 * @param checkpointId    Checkpoint 唯一标识
 * @param runId           运行 ID
 * @param threadId        线程 ID
 * @param userId          用户 ID
 * @param executionType   执行类型
 * @param purpose         Checkpoint 用途
 * @param agentName       Agent 名称
 * @param nodeName        恢复节点名
 * @param stateData       状态数据不可变快照
 * @param pendingApproval 待审批记录（SUSPENDED 时必不为 null）
 * @param status          Checkpoint 状态
 * @param version         版本号，从 0 开始
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 */
public record AgentCheckpoint(
        String checkpointId,
        String runId,
        String threadId,
        String userId,
        CheckpointExecutionType executionType,
        CheckpointPurpose purpose,
        String agentName,
        String nodeName,
        Map<String, Object> stateData,
        PendingApproval pendingApproval,
        CheckpointStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 兼容构造器，默认 purpose=HITL_RECOVERY。
     * <p>
     * 只用于现有 HITL 调用点的渐进迁移。
     * 不得让普通调用在未指定 purpose 时默认 THREAD_MEMORY。
     */
    public AgentCheckpoint(
            String checkpointId,
            String runId,
            String threadId,
            String userId,
            CheckpointExecutionType executionType,
            String agentName,
            String nodeName,
            Map<String, Object> stateData,
            PendingApproval pendingApproval,
            CheckpointStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(checkpointId, runId, threadId, userId, executionType,
                CheckpointPurpose.HITL_RECOVERY,
                agentName, nodeName, stateData, pendingApproval, status,
                version, createdAt, updatedAt);
    }

    public AgentCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId must not be null");
        if (checkpointId.isBlank()) {
            throw new IllegalArgumentException("checkpointId must not be blank");
        }
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(threadId, "threadId must not be null");
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        Objects.requireNonNull(executionType, "executionType must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(agentName, "agentName must not be null");
        if (agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        if (nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }

        // SUSPENDED 必须有 pendingApproval
        if (status == CheckpointStatus.SUSPENDED && pendingApproval == null) {
            throw new IllegalArgumentException(
                    "pendingApproval must not be null when status is SUSPENDED");
        }
        // RESUMING 必须有 pendingApproval（已决策版本）
        if (status == CheckpointStatus.RESUMING && pendingApproval == null) {
            throw new IllegalArgumentException(
                    "pendingApproval must not be null when status is RESUMING");
        }
        // COMPLETED/FAILED 不得有 pendingApproval
        if ((status == CheckpointStatus.COMPLETED || status == CheckpointStatus.FAILED)
                && pendingApproval != null) {
            throw new IllegalArgumentException(
                    "pendingApproval must be null when status is " + status);
        }

        // stateData 防御性深拷贝，确保不可变
        if (stateData == null) {
            throw new IllegalArgumentException("stateData must not be null");
        }
        stateData = deepCopyState(stateData);
    }

    /**
     * 防御性深拷贝状态数据。
     * <p>
     * 对已知的 List、Set、Map 做防御性复制，防止原 State 后续变化修改 Checkpoint。
     * 不保存 CompiledGraph、Gateway、Registry、Spring Bean、模型客户端或异常。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyState(Map<String, Object> original) {
        Map<String, Object> copy = new HashMap<>(original.size());
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 递归深拷贝值，确保嵌套集合也不可变。
     */
    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> inner = new HashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String key) {
                    inner.put(key, deepCopyValue(e.getValue()));
                }
            }
            return Collections.unmodifiableMap(inner);
        } else if (value instanceof List<?> l) {
            List<Object> inner = new ArrayList<>(l.size());
            for (Object item : l) {
                inner.add(deepCopyValue(item));
            }
            return List.copyOf(inner);
        } else if (value instanceof Set<?> s) {
            return Set.copyOf(s);
        }
        return value;
    }
}
