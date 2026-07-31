package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.supervisor.SupervisorChildRunLink;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionLease;
import com.ksyun.agent.runtime.react.ReactResumeEngine;
import com.ksyun.agent.runtime.react.ReactResumeResult;
import com.ksyun.agent.runtime.react.ThreadExecutionOutcome;
import com.ksyun.agent.runtime.supervisor.SupervisorResumeEngine;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorChildRunLinkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * 审批恢复应用服务。
 * <p>
 * 职责：
 * 1. 接收 ApprovalDecisionCommand
 * 2. 根据已认证 UserSession 查找待审批 Checkpoint
 * 3. 验证 Checkpoint 属于当前 userId
 * 4. 区分独立 React Agent 恢复和嵌套 Supervisor 恢复
 * 5. 独立 React：直接恢复子 Agent
 * 6. 嵌套 Supervisor：恢复子 Agent 后继续恢复父 Supervisor
 * 7. 恢复完成后保存 THREAD_MEMORY
 * 8. 精确清理目标 HITL_RECOVERY
 * 9. 返回 ApprovalResumeResult（统一覆盖两种场景）
 * <p>
 * 约束：
 * - 不在 Application Service 中伪造最终回答
 * - 操作者身份来自已验证 UserSession
 * - 不从请求 Body 获取 userId
 * - 不改变 approve/reject 幂等语义
 * - 不改变版本冲突 409 语义
 * - 不得把 THREAD_MEMORY 加入待审批列表
 */
public class ApprovalResumeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeApplicationService.class);

    private final ApprovalDecisionService decisionService;
    private final ReactResumeEngine resumeEngine;
    private final SupervisorResumeEngine supervisorResumeEngine;
    private final CheckpointStore checkpointStore;
    private final ThreadExecutionCoordinator threadExecutionCoordinator;
    private final ThreadConversationCheckpointService threadConversationCheckpointService;
    private final SupervisorChildRunLinkResolver linkResolver;

    public ApprovalResumeApplicationService(ApprovalDecisionService decisionService,
                                              ReactResumeEngine resumeEngine,
                                              SupervisorResumeEngine supervisorResumeEngine,
                                              CheckpointStore checkpointStore,
                                              ThreadExecutionCoordinator threadExecutionCoordinator,
                                              ThreadConversationCheckpointService threadConversationCheckpointService,
                                              SupervisorChildRunLinkResolver linkResolver) {
        this.decisionService = Objects.requireNonNull(decisionService);
        this.resumeEngine = Objects.requireNonNull(resumeEngine);
        this.supervisorResumeEngine = supervisorResumeEngine;
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.threadExecutionCoordinator = Objects.requireNonNull(threadExecutionCoordinator);
        this.threadConversationCheckpointService = Objects.requireNonNull(threadConversationCheckpointService);
        this.linkResolver = Objects.requireNonNull(linkResolver);
    }

    /**
     * 处理审批决定并恢复执行。
     * <p>
     * 自动区分：
     * - 独立 React Agent 恢复（子 Checkpoint 无 Link）
     * - 嵌套 Supervisor 恢复（子 Checkpoint 包含 Link）
     * <p>
     * 返回 ApprovalResumeResult，统一覆盖两种场景。
     */
    public ApprovalResumeResult decideAndResume(UserSession operator, ApprovalDecisionCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. 根据 runId 查找待审批 Checkpoint（只读）
        AgentCheckpoint checkpoint = checkpointStore.load(command.runId())
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found for runId: " + command.runId()));

        // 2. 验证 Checkpoint 属于当前 userId
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found for runId: " + command.runId());
        }

        // 2.5 拒绝直接对 SUPERVISOR Checkpoint 执行审批恢复
        if (checkpoint.executionType() == CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Supervisor checkpoint cannot be directly approved or resumed");
        }

        // 3. 读取 threadId
        String threadId = checkpoint.threadId();
        String userId = operator.userId();

        // 4. 检测是否嵌套 Supervisor 恢复
        SupervisorChildRunLink link = tryResolveLink(checkpoint);

        if (link != null) {
            // 嵌套 Supervisor 恢复路径
            return decideAndResumeNested(operator, command, userId, threadId, link);
        } else {
            // 独立 React Agent 恢复路径
            return decideAndResumeStandalone(operator, command, userId, threadId);
        }
    }

    /**
     * 仅记录审批决定，不恢复执行。
     */
    public ApprovalDecisionResult decide(UserSession operator, ApprovalDecisionCommand command) {
        return decisionService.decide(operator, command);
    }

    // ---- 内部方法 ----

    /**
     * 尝试从子 Checkpoint 中解析 SupervisorChildRunLink。
     * <p>
     * 解析失败时返回 null（独立 React Agent 场景）。
     * 不抛异常，因为独立 React Agent Checkpoint 不包含 Link。
     */
    private SupervisorChildRunLink tryResolveLink(AgentCheckpoint childCheckpoint) {
        try {
            return linkResolver.resolve(childCheckpoint);
        } catch (AgentFrameworkException e) {
            // 解析失败表示独立 React Agent，不是嵌套场景
            log.debug("No SupervisorChildRunLink found in checkpoint: runId={}, treating as standalone React",
                    childCheckpoint.runId());
            return null;
        }
    }

    /**
     * 独立 React Agent 恢复路径。
     * <p>
     * 与原有逻辑一致，但返回 ApprovalResumeResult。
     * 使用子 Agent 的 threadId 作为 Lease Key。
     */
    private ApprovalResumeResult decideAndResumeStandalone(
            UserSession operator,
            ApprovalDecisionCommand command,
            String userId,
            String threadId) {

        ThreadExecutionLease lease = threadExecutionCoordinator.acquire(userId, threadId);
        try {
            ApprovalDecisionResult decisionResult = decisionService.decide(operator, command);

            ThreadExecutionOutcome outcome = resumeEngine.resumeThread(command.runId(), operator);
            AgentResult agentResult = outcome.result();

            handleThreadSync(command.runId(), userId, threadId, outcome, agentResult);

            ReactResumeResult reactResult = ReactResumeResult.from(command.runId(), threadId, agentResult);
            return ApprovalResumeResult.fromReactResult(reactResult);
        } finally {
            lease.close();
        }
    }

    /**
     * 嵌套 Supervisor 恢复路径。
     * <p>
     * 流程：
     * 1. 使用父 Supervisor threadId 作为 Lease Key
     * 2. 记录审批决定
     * 3. 恢复子 Agent（ReactResumeEngine）
     * 4. 子 Agent 恢复后继续恢复父 Supervisor（SupervisorResumeEngine）
     * 5. 线程状态同步
     * 6. 精确清理父子 HITL Checkpoint
     * 7. 返回 ApprovalResumeResult
     * <p>
     * 子 Agent 恢复在父 SupervisorResumeEngine 内部完成，
     * 不需要单独调用 ReactResumeEngine。
     * 但审批决定需要先对子 Checkpoint 执行。
     */
    private ApprovalResumeResult decideAndResumeNested(
            UserSession operator,
            ApprovalDecisionCommand command,
            String userId,
            String childThreadId,
            SupervisorChildRunLink link) {

        // 使用父 Supervisor threadId 作为 Lease Key
        String parentThreadId = link.parentThreadId();
        ThreadExecutionLease lease = threadExecutionCoordinator.acquire(userId, parentThreadId);
        try {
            // 1. 记录子 Checkpoint 的审批决定
            ApprovalDecisionResult decisionResult = decisionService.decide(operator, command);

            // 2. 恢复父 Supervisor（内部会恢复子 Agent）
            ThreadExecutionOutcome supervisorOutcome =
                    supervisorResumeEngine.resumeSupervisor(link.parentRunId(), operator);

            AgentResult supervisorResult = supervisorOutcome.result();

            // 3. 线程状态同步（使用父 Supervisor threadId）
            handleThreadSync(link.parentRunId(), userId, parentThreadId, supervisorOutcome, supervisorResult);

            // 4. 精确清理子 Agent HITL Checkpoint
            cleanupChildHitlCheckpoint(command.runId(), userId, childThreadId);

            // 5. 返回 Supervisor 恢复结果
            return ApprovalResumeResult.fromSupervisorResult(
                    supervisorResult, link.parentRunId(), parentThreadId);
        } finally {
            lease.close();
        }
    }

    /**
     * 处理 HITL 恢复后的线程状态同步。
     * <p>
     * - conversationState 存在且稳定：保存新 THREAD_MEMORY
     * - conversationState 为空且再次 SUSPENDED：保留新 HITL_RECOVERY，不保存 THREAD_MEMORY
     * - conversationState 为空且 FAILED：不覆盖旧 THREAD_MEMORY
     */
    private void handleThreadSync(
            String runId,
            String userId,
            String threadId,
            ThreadExecutionOutcome outcome,
            AgentResult agentResult) {

        if (outcome.conversationState().isPresent()) {
            try {
                threadConversationCheckpointService.save(
                        userId, threadId, runId, outcome.conversationState().get());

                cleanupTargetHitlCheckpoint(userId, threadId, runId);

                log.info("HITL resume thread synchronized: runId={}, threadId={}, userId={}",
                        runId, threadId, userId);
            } catch (AgentFrameworkException e) {
                log.error("HITL resume thread synchronization failed: runId={}, threadId={}, errorCode={}",
                        runId, threadId, e.getErrorCode());
                throw e;
            }
        } else if (agentResult.status() == RunStatus.SUSPENDED) {
            log.info("HITL resume resulted in re-suspension: runId={}, threadId={}", runId, threadId);
        } else {
            log.info("HITL resume produced no stable state: runId={}, threadId={}, status={}",
                    runId, threadId, agentResult.status());
        }
    }

    /**
     * 精确清理子 Agent HITL Checkpoint。
     * <p>
     * 嵌套恢复场景下，子 Checkpoint 已被 SupervisorResumeEngine 内部的
     * ReactResumeEngine 处理过，但 THREAD_MEMORY 保存和 HITL 清理
     * 可能需要在此补充。
     */
    private void cleanupChildHitlCheckpoint(String childRunId, String userId, String childThreadId) {
        try {
            AgentCheckpoint childCp = checkpointStore.load(childRunId).orElse(null);
            if (childCp == null) {
                return;
            }

            // 只清理 RESUMING 状态的子 Checkpoint
            if (childCp.status() != CheckpointStatus.RESUMING) {
                return;
            }

            // 条件删除
            boolean deleted = checkpointStore.deleteIfVersionMatches(
                    childCp.runId(), childCp.checkpointId(), childCp.version());
            if (deleted) {
                log.info("Child HITL checkpoint cleaned up after nested resume: childRunId={}", childRunId);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup child HITL checkpoint: childRunId={}", childRunId, e);
        }
    }

    /**
     * THREAD_MEMORY 保存成功后，精确清理本次恢复对应的 HITL_RECOVERY。
     */
    private void cleanupTargetHitlCheckpoint(String userId, String threadId, String runId) {
        AgentCheckpoint target = checkpointStore.findByThreadId(
                        userId, threadId, CheckpointPurpose.HITL_RECOVERY)
                .stream()
                .filter(checkpoint -> runId.equals(checkpoint.runId()))
                .filter(checkpoint -> checkpoint.status() == CheckpointStatus.RESUMING)
                .findFirst()
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Target HITL checkpoint is no longer resumable"));

        boolean deleted = checkpointStore.deleteIfVersionMatches(
                target.runId(), target.checkpointId(), target.version());

        if (!deleted) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Target HITL checkpoint changed during cleanup");
        }
    }
}
