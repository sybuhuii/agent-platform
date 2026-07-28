package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.InterruptPayload;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 待审批查询服务，纯 Java 实现。
 * <p>
 * 依赖：CheckpointStore
 * <p>
 * 职责：
 * - operator 不能为空且必须来自已验证 Session
 * - 通过 operator.userId 查询当前用户 Checkpoint
 * - 列表只返回 CheckpointStatus.SUSPENDED + ApprovalStatus.PENDING
 * - 不返回 RESUMING、COMPLETED、FAILED
 * - 不返回已 APPROVED 或已 REJECTED 但尚未恢复的数据
 * - 列表按 requestedAt 升序排列
 * - getPending 通过 runId 加载后必须校验归属
 * - 不存在和属于其他用户使用相同的安全 NOT_FOUND 语义
 * - 不得泄漏其他用户是否拥有该 runId
 * - 必须校验 Checkpoint 和 PendingApproval 结构
 * - 不调用模型、工具或恢复引擎
 * - 不修改或删除 Checkpoint
 * - 不依赖 Spring Web、HttpServletRequest 或 ThreadLocal
 * <p>
 * 线程安全、无状态。
 */
public class PendingApprovalQueryService {

    private final CheckpointStore checkpointStore;

    public PendingApprovalQueryService(CheckpointStore checkpointStore) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
    }

    /**
     * 查询当前用户待审批列表。
     * <p>
     * 只返回 SUSPENDED + PENDING。按 requestedAt 升序。
     *
     * @param operator 当前操作用户，来自已验证 Session
     * @return 不可变摘要列表
     */
    public Collection<PendingApprovalSummary> findPending(UserSession operator) {
        Objects.requireNonNull(operator, "operator must not be null");

        Collection<AgentCheckpoint> checkpoints = checkpointStore.findPendingByUserId(operator.userId());

        // findPendingByUserId 已经过滤 SUSPENDED + PENDING，但仍做防御性检查
        Collection<PendingApprovalSummary> summaries = new ArrayList<>();
        for (AgentCheckpoint cp : checkpoints) {
            // 防御性过滤：只返回 SUSPENDED + PENDING
            if (cp.status() != CheckpointStatus.SUSPENDED) {
                continue;
            }
            if (cp.pendingApproval() == null || cp.pendingApproval().status() != ApprovalStatus.PENDING) {
                continue;
            }

            summaries.add(toSummary(cp));
        }

        return Collections.unmodifiableList(new ArrayList<>(summaries));
    }

    /**
     * 查询指定 runId 的待审批详情。
     * <p>
     * 不存在或不属于当前用户时使用安全的 NOT_FOUND 语义，
     * 不得泄漏其他用户是否拥有该 runId。
     *
     * @param operator 当前操作用户
     * @param runId    运行 ID
     * @return 审批详情
     * @throws AgentFrameworkException CHECKPOINT_NOT_FOUND（不存在或非归属）
     */
    public PendingApprovalDetail getPending(UserSession operator, String runId) {
        Objects.requireNonNull(operator, "operator must not be null");
        if (runId == null || runId.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        AgentCheckpoint checkpoint = checkpointStore.load(runId).orElse(null);

        // 不存在或属于其他用户：统一安全 NOT_FOUND
        if (checkpoint == null || !checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 必须是 SUSPENDED
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 必须有 PendingApproval
        if (checkpoint.pendingApproval() == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        // 只返回 PENDING 状态的审批
        if (checkpoint.pendingApproval().status() != ApprovalStatus.PENDING) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found");
        }

        return toDetail(checkpoint);
    }

    private PendingApprovalSummary toSummary(AgentCheckpoint cp) {
        PendingApproval approval = cp.pendingApproval();
        InterruptPayload payload = approval.payload();

        return new PendingApprovalSummary(
                cp.runId(),
                cp.threadId(),
                cp.agentName(),
                approval.approvalId(),
                payload.operationType().name(),
                payload.operationName(),
                payload.riskLevel().name(),
                payload.reason(),
                payload.requestedAt(),
                approval.status()
        );
    }

    private PendingApprovalDetail toDetail(AgentCheckpoint cp) {
        PendingApproval approval = cp.pendingApproval();
        InterruptPayload payload = approval.payload();

        // safeArguments 使用 Checkpoint 中已脱敏的不可变快照
        Map<String, Object> safeArgs = payload.safeArguments() != null
                ? Collections.unmodifiableMap(payload.safeArguments())
                : Map.of();

        return new PendingApprovalDetail(
                cp.runId(),
                cp.threadId(),
                cp.agentName(),
                approval.approvalId(),
                payload.operationType().name(),
                payload.operationName(),
                payload.riskLevel().name(),
                payload.reason(),
                payload.requestedAt(),
                approval.status(),
                cp.nodeName(),
                safeArgs,
                cp.createdAt(),
                cp.updatedAt(),
                cp.version()
        );
    }
}
