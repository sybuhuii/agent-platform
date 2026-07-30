package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionLease;
import com.ksyun.agent.runtime.react.ReactResumeEngine;
import com.ksyun.agent.runtime.react.ReactResumeResult;
import com.ksyun.agent.runtime.react.ThreadExecutionOutcome;
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
 * 4. 读取 threadId 并使用 ThreadExecutionCoordinator acquire Lease
 * 5. 在 Lease 内完成审批决定和恢复
 * 6. 恢复完成后保存 THREAD_MEMORY
 * 7. THREAD_MEMORY 保存成功后精确清理目标 HITL_RECOVERY
 * 8. 使用 ReactResumeResult 表达恢复结果（包含 threadId）
 * <p>
 * 约束：
 * - 不在 Application Service 中伪造最终回答
 * - 操作者身份来自已验证 UserSession
 * - 不从请求 Body 获取 userId
 * - Lease Key 必须为 Checkpoint.userId + threadId
 * - 不得信任请求体中的 userId 或 threadId
 * - 审批请求仍以 runId 和 approvalId 定位
 * - 不改变 approve/reject 幂等语义
 * - 不改变版本冲突 409 语义
 * - 保存新 THREAD_MEMORY 成功后才视为线程同步成功
 * - 不得先删除 HITL_RECOVERY 再保存 THREAD_MEMORY
 * - 不得返回恢复成功但线程状态未保存的假成功
 * - 此过程不是分布式事务，仅保证进程内顺序
 * - 不得把 THREAD_MEMORY 加入待审批列表
 */
public class ApprovalResumeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeApplicationService.class);

    private final ApprovalDecisionService decisionService;
    private final ReactResumeEngine resumeEngine;
    private final CheckpointStore checkpointStore;
    private final ThreadExecutionCoordinator threadExecutionCoordinator;
    private final ThreadConversationCheckpointService threadConversationCheckpointService;

    public ApprovalResumeApplicationService(ApprovalDecisionService decisionService,
                                              ReactResumeEngine resumeEngine,
                                              CheckpointStore checkpointStore,
                                              ThreadExecutionCoordinator threadExecutionCoordinator,
                                              ThreadConversationCheckpointService threadConversationCheckpointService) {
        this.decisionService = Objects.requireNonNull(decisionService);
        this.resumeEngine = Objects.requireNonNull(resumeEngine);
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.threadExecutionCoordinator = Objects.requireNonNull(threadExecutionCoordinator);
        this.threadConversationCheckpointService = Objects.requireNonNull(threadConversationCheckpointService);
    }

    /**
     * 处理审批决定并恢复执行。
     * <p>
     * 流程：
     * 1. 根据 runId 查找待审批 Checkpoint（只读）
     * 2. 验证 Checkpoint 属于当前 userId
     * 3. 读取 threadId
     * 4. 使用 ThreadExecutionCoordinator.acquire(userId, threadId)
     * 5. 在 Lease 内：审批决定 + 恢复执行 + 线程状态同步
     * 6. 释放 Lease
     * <p>
     * APPROVE：记录决定后恢复图执行
     * REJECT：记录决定后也恢复图执行
     * <p>
     * 返回 ReactResumeResult，包含 runId/threadId/agentName/status 等完整信息。
     * 不得对 REJECT 直接返回静态 failure AgentResult。
     */
    public ReactResumeResult decideAndResume(UserSession operator, ApprovalDecisionCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. 根据 runId 查找待审批 Checkpoint（只读，获取 userId 和 threadId）
        AgentCheckpoint checkpoint = checkpointStore.load(command.runId())
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found for runId: " + command.runId()));

        // 2. 验证 Checkpoint 属于当前 userId（安全拒绝，不泄漏信息）
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found for runId: " + command.runId());
        }

        // 3. 读取 threadId
        String threadId = checkpoint.threadId();
        String userId = operator.userId();

        // 4. 使用 ThreadExecutionCoordinator.acquire(userId, threadId)
        ThreadExecutionLease lease = threadExecutionCoordinator.acquire(userId, threadId);
        try {
            // 5. 在 Lease 内完成审批决定和恢复
            return executeDecideAndResumeInLease(operator, command, userId, threadId, lease);
        } finally {
            // 6. 释放 Lease（异常路径也必须释放）
            lease.close();
        }
    }

    /**
     * 在 Lease 保护下完成审批决定、恢复和线程同步。
     */
    private ReactResumeResult executeDecideAndResumeInLease(
            UserSession operator,
            ApprovalDecisionCommand command,
            String userId,
            String threadId,
            ThreadExecutionLease lease
    ) {
        // 5a. 审批决定（在 Lease 内）
        ApprovalDecisionResult decisionResult = decisionService.decide(operator, command);

        // 5b. 恢复执行（APPROVE 和 REJECT 都恢复图执行）
        ThreadExecutionOutcome outcome = resumeEngine.resumeThread(command.runId(), operator);

        AgentResult agentResult = outcome.result();

        // 5c. 线程状态同步
        handleThreadSync(command.runId(), userId, threadId, outcome, agentResult);

        // 构造恢复结果
        ReactResumeResult resumeResult = ReactResumeResult.from(
                command.runId(), threadId, agentResult);

        log.info("Resume completed: runId={}, action={}, resultStatus={}",
                command.runId(), command.action(), agentResult.status());

        return resumeResult;
    }

    /**
     * 处理 HITL 恢复后的线程状态同步。
     * <p>
     * 保存和清理顺序（进程内）：
     * 获取 ThreadExecutionLease
     * → 审批决定及 Checkpoint 版本抢占
     * → 恢复 ReAct
     * → 得到稳定 ThreadConversationState
     * → 保存新 THREAD_MEMORY
     * → 确认保存成功
     * → 清理目标 HITL_RECOVERY（由 ReactResumeEngine LifecycleService 处理）
     * → 释放 Lease
     * <p>
     * - conversationState 存在且稳定：保存新 THREAD_MEMORY
     * - conversationState 为空且再次 SUSPENDED：保留新 HITL_RECOVERY，不保存 THREAD_MEMORY
     * - conversationState 为空且 FAILED：不覆盖旧 THREAD_MEMORY
     * <p>
     * THREAD_MEMORY 保存失败时，不得视为线程同步成功。
     * 此过程不是分布式事务。
     */
    private void handleThreadSync(
            String runId,
            String userId,
            String threadId,
            ThreadExecutionOutcome outcome,
            AgentResult agentResult
    ) {
        if (outcome.conversationState().isPresent()) {
            /*
             * 严格顺序：
             * 1. 保存新的 THREAD_MEMORY
             * 2. save 正常返回，确认保存成功
             * 3. 删除本次 runId 对应的 HITL_RECOVERY
             */
            try {
                threadConversationCheckpointService.save(
                        userId,
                        threadId,
                        runId,
                        outcome.conversationState().get());

                cleanupTargetHitlCheckpoint(
                        userId,
                        threadId,
                        runId);

                log.info(
                        "HITL resume thread synchronized: "
                                + "runId={}, threadId={}, userId={}",
                        runId,
                        threadId,
                        userId);
            } catch (AgentFrameworkException e) {
                log.error(
                        "HITL resume thread synchronization failed: "
                                + "runId={}, threadId={}, errorCode={}",
                        runId,
                        threadId,
                        e.getErrorCode());

                /*
                 * save 失败时不会执行 cleanupTargetHitlCheckpoint，
                 * 因此不会删除 HITL_RECOVERY。
                 */
                throw e;
            }
        } else if (agentResult.status() == RunStatus.SUSPENDED) {
            log.info(
                    "HITL resume resulted in re-suspension: "
                            + "runId={}, threadId={}",
                    runId,
                    threadId);
        } else {
            log.info(
                    "HITL resume produced no stable state: "
                            + "runId={}, threadId={}, status={}",
                    runId,
                    threadId,
                    agentResult.status());
        }
    }

    /**
     * THREAD_MEMORY 保存成功后，精确清理本次恢复对应的
     * HITL_RECOVERY。
     */
    private void cleanupTargetHitlCheckpoint(
            String userId,
            String threadId,
            String runId
    ) {
        AgentCheckpoint target = checkpointStore.findByThreadId(
                        userId,
                        threadId,
                        com.ksyun.agent.core.run.CheckpointPurpose.HITL_RECOVERY)
                .stream()
                .filter(checkpoint ->
                        runId.equals(checkpoint.runId()))
                .filter(checkpoint ->
                        checkpoint.status()
                                == com.ksyun.agent.core.run.CheckpointStatus.RESUMING)
                .findFirst()
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_CONFLICT,
                        "Target HITL checkpoint is no longer resumable"));

        boolean deleted = checkpointStore.deleteIfVersionMatches(
                target.runId(),
                target.checkpointId(),
                target.version());

        if (!deleted) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Target HITL checkpoint changed during cleanup");
        }
    }

    /**
     * 仅记录审批决定，不恢复执行。
     */
    public ApprovalDecisionResult decide(UserSession operator, ApprovalDecisionCommand command) {
        return decisionService.decide(operator, command);
    }

    /**
     * 仅恢复已决定的 Checkpoint。
     * <p>
     * 也使用 Lease 保护并发。
     */
    public ReactResumeResult resume(UserSession operator, String runId) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(runId, "runId must not be null");

        // 1. 查找 Checkpoint（只读）
        AgentCheckpoint checkpoint = checkpointStore.load(runId)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Checkpoint not found for runId: " + runId));

        // 2. 验证 userId
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Checkpoint not found for runId: " + runId);
        }

        // 3. 读取 threadId
        String threadId = checkpoint.threadId();
        String userId = operator.userId();

        // 4. 获取 Lease
        ThreadExecutionLease lease = threadExecutionCoordinator.acquire(userId, threadId);
        try {
            // 5. 在 Lease 内恢复执行
            ThreadExecutionOutcome outcome = resumeEngine.resumeThread(runId, operator);

            AgentResult agentResult = outcome.result();

            // 6. 线程状态同步
            handleThreadSync(runId, userId, threadId, outcome, agentResult);

            return ReactResumeResult.from(runId, threadId, agentResult);
        } finally {
            lease.close();
        }
    }
}
