package com.ksyun.agent.runtime.supervisor.checkpoint;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorChildExecutionStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildRunLink;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import com.ksyun.agent.runtime.supervisor.SupervisorNodeNames;
import com.ksyun.agent.runtime.supervisor.SupervisorStateKeys;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Supervisor Checkpoint 服务，纯 Java 实现。
 * <p>
 * 不添加 Spring 注解。
 * <p>
 * 职责：
 * - 创建父 SUPERVISOR / HITL_RECOVERY Checkpoint
 * - 幂等保存
 * - 再次暂停时的安全更新基础
 * - 加载和校验
 * <p>
 * 不实现恢复。不删除旧 Checkpoint。
 */
public class SupervisorCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorCheckpointService.class);

    private final CheckpointStore checkpointStore;
    private final CheckpointIdGenerator checkpointIdGenerator;
    private final SupervisorCheckpointValidator supervisorCheckpointValidator;
    private final SupervisorCheckpointStateMapper stateMapper;
    private final Clock clock;

    public SupervisorCheckpointService(CheckpointStore checkpointStore,
                                        CheckpointIdGenerator checkpointIdGenerator,
                                        SupervisorCheckpointValidator supervisorCheckpointValidator,
                                        SupervisorCheckpointStateMapper stateMapper,
                                        Clock clock) {
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
        this.checkpointIdGenerator = Objects.requireNonNull(checkpointIdGenerator);
        this.supervisorCheckpointValidator = Objects.requireNonNull(supervisorCheckpointValidator);
        this.stateMapper = Objects.requireNonNull(stateMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 挂起 Supervisor 运行，保存父 Checkpoint。
     * <p>
     * 执行流程：
     * 1. 从 Supervisor State 获取父 RunContext 和 SupervisorDefinition
     * 2. 校验 nodeName == DISPATCH_AGENTS
     * 3. 校验 suspendedChildren 非空
     * 4. 按 dispatchIndex 稳定排序
     * 5. 对每个暂停子任务读取 runLink.childRunId
     * 6. 通过 CheckpointStore.load(childRunId) 加载子 Checkpoint
     * 7. 校验子 Checkpoint
     * 8. 选取代表性 PendingApproval
     * 9. 生成或复用父 checkpointId
     * 10. 使用 Mapper 创建父 stateData
     * 11. 创建 SUPERVISOR / HITL_RECOVERY Checkpoint
     * 12. 调用 SupervisorCheckpointValidator
     * 13. 保存或条件更新
     * 14. 返回保存后的 AgentCheckpoint
     *
     * @param state             当前 SupervisorAgentState
     * @param dispatchTasks     本轮最新 DISPATCH_TASKS
     * @param suspendedChildren 本轮最新 SUSPENDED_CHILDREN
     * @param nodeName          调用节点名（必须为 DISPATCH_AGENTS）
     * @return 保存后的 AgentCheckpoint
     */
    public AgentCheckpoint suspend(
            SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren,
            String nodeName) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(dispatchTasks, "dispatchTasks must not be null");
        Objects.requireNonNull(suspendedChildren, "suspendedChildren must not be null");
        Objects.requireNonNull(nodeName, "nodeName must not be null");

        // 1. 从 Supervisor State 获取父 RunContext 和 SupervisorDefinition
        RunContext parentContext = SupervisorStateKeys.getRunContext(state);
        SupervisorDefinition definition = SupervisorStateKeys.getSupervisorDefinition(state);

        // 2. 校验 nodeName == DISPATCH_AGENTS
        if (!SupervisorNodeNames.DISPATCH_AGENTS.equals(nodeName)) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor checkpoint nodeName must be DISPATCH_AGENTS, got " + nodeName);
        }

        // 3. 校验 suspendedChildren 非空
        if (suspendedChildren.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor checkpoint requires non-empty suspendedChildren");
        }

        // 4. 按 dispatchIndex 稳定排序
        List<SupervisorChildExecution> sortedSuspended = suspendedChildren.stream()
                .sorted(Comparator.comparingInt(SupervisorChildExecution::dispatchIndex))
                .toList();

        // 5-7. 对每个暂停子任务加载并校验子 Checkpoint
        PendingApproval representativeApproval = null;
        for (SupervisorChildExecution exec : sortedSuspended) {
            SupervisorChildRunLink runLink = exec.runLink();
            String childRunId = runLink.childRunId();

            AgentCheckpoint childCheckpoint = loadAndValidateChildCheckpoint(
                    childRunId, exec, parentContext);

            // 8. 选取代表性 PendingApproval（第一个暂停子任务）
            if (representativeApproval == null) {
                representativeApproval = childCheckpoint.pendingApproval();
                if (representativeApproval == null) {
                    throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                            "Child checkpoint missing pendingApproval: childRunId=" + childRunId);
                }
            }

            // 从子 Checkpoint stateData 中读取 AgentTask.context 中的 Link 并校验
            validateChildLinkFromCheckpoint(childCheckpoint, runLink, exec);
        }

        // 9. 生成或复用父 checkpointId
        Optional<AgentCheckpoint> existingOpt = checkpointStore.load(parentContext.runId());

        if (existingOpt.isPresent()) {
            return handleExistingCheckpoint(existingOpt.get(), state, dispatchTasks,
                    sortedSuspended, representativeApproval, parentContext, definition);
        }

        // 首次保存
        return createNewCheckpoint(state, dispatchTasks, sortedSuspended,
                representativeApproval, parentContext, definition);
    }

    /**
     * 9.5 加载方法：按 parentRunId 加载父 Checkpoint。
     */
    public Optional<AgentCheckpoint> loadCheckpoint(String parentRunId) {
        if (parentRunId == null || parentRunId.isBlank()) {
            return Optional.empty();
        }
        return checkpointStore.load(parentRunId);
    }

    /**
     * 9.5 严格加载方法：校验 userId 和 Checkpoint 类型。
     * <p>
     * 后续恢复可使用的严格加载方法。
     * 本批只提供能力，不执行恢复。
     */
    public AgentCheckpoint loadRequiredForResume(String userId, String parentRunId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(parentRunId, "parentRunId must not be null");

        AgentCheckpoint checkpoint = checkpointStore.load(parentRunId)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Supervisor checkpoint not found: runId=" + parentRunId));

        // checkpoint.userId == 当前已认证 userId
        if (!checkpoint.userId().equals(userId)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Supervisor checkpoint not found");
        }

        // executionType == SUPERVISOR
        if (checkpoint.executionType() != CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint executionType must be SUPERVISOR, got " + checkpoint.executionType());
        }

        // purpose == HITL_RECOVERY
        if (checkpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint purpose must be HITL_RECOVERY, got " + checkpoint.purpose());
        }

        // status == SUSPENDED 或 RESUMING
        if (checkpoint.status() != CheckpointStatus.SUSPENDED
                && checkpoint.status() != CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Supervisor checkpoint status must be SUSPENDED or RESUMING, got " + checkpoint.status());
        }

        return checkpoint;
    }

    /**
     * 原子抢占 SUSPENDED → RESUMING。
     * <p>
     * 使用 version 条件更新，并发恢复只有一个成功。
     * 必须在调用前通过 Validator 校验。
     * 不实现审批决定。不调用模型和工具。
     *
     * @param parentRunId 父 Supervisor runId
     * @param operator    当前操作用户
     * @return 抢占后的 RESUMING Checkpoint
     * @throws AgentFrameworkException CHECKPOINT_NOT_FOUND / RUN_ALREADY_RESUMING / CHECKPOINT_CONFLICT
     */
    public AgentCheckpoint acquireForResume(String parentRunId, UserSession operator) {
        Objects.requireNonNull(parentRunId, "parentRunId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        AgentCheckpoint checkpoint = checkpointStore.load(parentRunId)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Supervisor checkpoint not found"));

        // 不匹配用户使用安全 NOT_FOUND
        if (!checkpoint.userId().equals(operator.userId())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_FOUND,
                    "Supervisor checkpoint not found");
        }

        // executionType == SUPERVISOR
        if (checkpoint.executionType() != CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint executionType must be SUPERVISOR");
        }

        // 必须是 SUSPENDED 状态
        if (checkpoint.status() == CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Supervisor checkpoint is already in RESUMING state");
        }
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Supervisor checkpoint is not in SUSPENDED state: " + checkpoint.status());
        }

        // 原子更新 SUSPENDED → RESUMING
        long expectedVersion = checkpoint.version();
        Instant now = clock.instant();

        AgentCheckpoint resumingCheckpoint = new AgentCheckpoint(
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.threadId(),
                checkpoint.userId(),
                checkpoint.executionType(),
                checkpoint.purpose(),
                checkpoint.agentName(),
                checkpoint.nodeName(),
                checkpoint.stateData(),
                checkpoint.pendingApproval(),
                CheckpointStatus.RESUMING,
                expectedVersion + 1,
                checkpoint.createdAt(),
                now
        );

        boolean success = checkpointStore.updateIfVersionMatches(resumingCheckpoint, expectedVersion);
        if (!success) {
            throw new AgentFrameworkException(AgentErrorCode.RUN_ALREADY_RESUMING,
                    "Supervisor checkpoint version conflict or already resuming: runId=" + parentRunId);
        }

        log.info("Supervisor checkpoint acquired for resume: runId={}, checkpointId={}, version={}",
                parentRunId, checkpoint.checkpointId(), expectedVersion + 1);

        return resumingCheckpoint;
    }

    /**
     * 完成：RESUMING → COMPLETED 条件更新，再条件删除。
     *
     * @param resumingCheckpoint 当前处于 RESUMING 的 Checkpoint
     */
    public void complete(AgentCheckpoint resumingCheckpoint) {
        Objects.requireNonNull(resumingCheckpoint, "resumingCheckpoint must not be null");

        long expectedVersion = resumingCheckpoint.version();
        Instant now = clock.instant();

        AgentCheckpoint completedCp = new AgentCheckpoint(
                resumingCheckpoint.checkpointId(),
                resumingCheckpoint.runId(),
                resumingCheckpoint.threadId(),
                resumingCheckpoint.userId(),
                resumingCheckpoint.executionType(),
                resumingCheckpoint.purpose(),
                resumingCheckpoint.agentName(),
                resumingCheckpoint.nodeName(),
                resumingCheckpoint.stateData(),
                null,
                CheckpointStatus.COMPLETED,
                expectedVersion + 1,
                resumingCheckpoint.createdAt(),
                now
        );

        boolean updated = checkpointStore.updateIfVersionMatches(completedCp, expectedVersion);
        if (!updated) {
            log.warn("Supervisor checkpoint version conflict during COMPLETED: runId={}", resumingCheckpoint.runId());
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Supervisor checkpoint version conflict during completion: runId=" + resumingCheckpoint.runId());
        }

        boolean deleted = checkpointStore.deleteIfVersionMatches(
                resumingCheckpoint.runId(), completedCp.checkpointId(), completedCp.version());
        if (deleted) {
            log.info("Supervisor checkpoint cleaned up after completion: runId={}", resumingCheckpoint.runId());
        }
    }

    /**
     * 失败：RESUMING → FAILED 条件更新。
     *
     * @param resumingCheckpoint 当前处于 RESUMING 的 Checkpoint
     * @param errorCode          安全错误码
     */
    public void fail(AgentCheckpoint resumingCheckpoint, AgentErrorCode errorCode) {
        Objects.requireNonNull(resumingCheckpoint, "resumingCheckpoint must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");

        long expectedVersion = resumingCheckpoint.version();
        Instant now = clock.instant();

        AgentCheckpoint failedCp = new AgentCheckpoint(
                resumingCheckpoint.checkpointId(),
                resumingCheckpoint.runId(),
                resumingCheckpoint.threadId(),
                resumingCheckpoint.userId(),
                resumingCheckpoint.executionType(),
                resumingCheckpoint.purpose(),
                resumingCheckpoint.agentName(),
                resumingCheckpoint.nodeName(),
                resumingCheckpoint.stateData(),
                null,
                CheckpointStatus.FAILED,
                expectedVersion + 1,
                resumingCheckpoint.createdAt(),
                now
        );

        boolean updated = checkpointStore.updateIfVersionMatches(failedCp, expectedVersion);
        if (!updated) {
            log.warn("Supervisor checkpoint version conflict during FAILED: runId={}", resumingCheckpoint.runId());
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Supervisor checkpoint version conflict during failure: runId=" + resumingCheckpoint.runId());
        }

        log.info("Supervisor checkpoint marked as FAILED: runId={}, errorCode={}", resumingCheckpoint.runId(), errorCode);
    }

    // ---- 内部方法 ----

    /**
     * 加载并校验子 Checkpoint。
     */
    private AgentCheckpoint loadAndValidateChildCheckpoint(
            String childRunId,
            SupervisorChildExecution exec,
            RunContext parentContext) {

        AgentCheckpoint childCheckpoint = checkpointStore.load(childRunId)
                .orElseThrow(() -> new AgentFrameworkException(
                        AgentErrorCode.CHECKPOINT_NOT_FOUND,
                        "Child checkpoint not found: childRunId=" + childRunId));

        // executionType == REACT_AGENT
        if (childCheckpoint.executionType() != CheckpointExecutionType.REACT_AGENT) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint executionType must be REACT_AGENT, got " + childCheckpoint.executionType());
        }

        // purpose == HITL_RECOVERY
        if (childCheckpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint purpose must be HITL_RECOVERY, got " + childCheckpoint.purpose());
        }

        // status == SUSPENDED
        if (childCheckpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint must be SUSPENDED, got " + childCheckpoint.status());
        }

        // pendingApproval 非空
        if (childCheckpoint.pendingApproval() == null) {
            throw new AgentFrameworkException(AgentErrorCode.APPROVAL_NOT_FOUND,
                    "Child checkpoint missing pendingApproval: childRunId=" + childRunId);
        }

        // pendingApproval.status == PENDING
        if (childCheckpoint.pendingApproval().status() != ApprovalStatus.PENDING) {
            throw new AgentFrameworkException(AgentErrorCode.APPROVAL_ALREADY_DECIDED,
                    "Child checkpoint approval is not PENDING: childRunId=" + childRunId);
        }

        // childCheckpoint.runId == runLink.childRunId
        if (!childCheckpoint.runId().equals(childRunId)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint runId mismatch");
        }

        // childCheckpoint.threadId == runLink.childThreadId
        if (!childCheckpoint.threadId().equals(exec.runLink().childThreadId())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint threadId mismatch");
        }

        // childCheckpoint.userId == parent RunContext.userId
        if (!childCheckpoint.userId().equals(parentContext.userId())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint userId mismatch");
        }

        // childCheckpoint.agentName == child execution.task.agentName
        if (!childCheckpoint.agentName().equals(exec.task().agentName())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint agentName mismatch");
        }

        // pendingApproval.approvalId == child execution.approvalId
        if (!childCheckpoint.pendingApproval().approvalId().equals(exec.approvalId())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint approvalId mismatch");
        }

        return childCheckpoint;
    }

    /**
     * 从子 Checkpoint stateData 中读取第一步保存的 AgentTask.context 中的 Link，
     * 并校验它与父状态中的 Link 完全一致。
     * <p>
     * 不只信任父 AgentResult.metadata。
     */
    private void validateChildLinkFromCheckpoint(
            AgentCheckpoint childCheckpoint,
            SupervisorChildRunLink expectedLink,
            SupervisorChildExecution exec) {

        Map<String, Object> childStateData = childCheckpoint.stateData();
        if (childStateData == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint stateData is null: childRunId=" + expectedLink.childRunId());
        }

        // 从子 Checkpoint stateData 中读取 TASK → AgentTask → context → SupervisorChildRunLink
        Object taskObj = childStateData.get(ReactStateKeys.TASK);
        if (!(taskObj instanceof AgentTask task)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint stateData missing or invalid TASK: childRunId=" + expectedLink.childRunId());
        }

        Object linkObj = task.context().get(SupervisorChildRunLink.TASK_CONTEXT_KEY);
        if (!(linkObj instanceof SupervisorChildRunLink childLink)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint AgentTask.context missing SupervisorChildRunLink: childRunId="
                            + expectedLink.childRunId());
        }

        // 校验 Link 完全一致
        if (!childLink.equals(expectedLink)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint Link does not match parent state: childRunId=" + expectedLink.childRunId());
        }
    }

    /**
     * 首次保存：创建 version=0 的 Checkpoint。
     */
    private AgentCheckpoint createNewCheckpoint(
            SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren,
            PendingApproval representativeApproval,
            RunContext parentContext,
            SupervisorDefinition definition) {

        String checkpointId = checkpointIdGenerator.generate();
        Instant now = clock.instant();

        // 使用 Mapper 创建父 stateData
        Map<String, Object> stateData = stateMapper.toStateData(
                state, dispatchTasks, suspendedChildren, checkpointId);

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                checkpointId,
                parentContext.runId(),
                parentContext.threadId(),
                parentContext.userId(),
                CheckpointExecutionType.SUPERVISOR,
                CheckpointPurpose.HITL_RECOVERY,
                definition.name(),
                SupervisorNodeNames.DISPATCH_AGENTS,
                stateData,
                representativeApproval,
                CheckpointStatus.SUSPENDED,
                0,
                now,
                now
        );

        // 12. 调用 SupervisorCheckpointValidator
        supervisorCheckpointValidator.validate(checkpoint);

        // 13. 保存
        checkpointStore.save(checkpoint);

        log.info("Supervisor checkpoint saved: checkpointId={}, runId={}, version=0, " +
                        "suspendedChildrenCount={}, approvalId={}",
                checkpointId, parentContext.runId(),
                suspendedChildren.size(), representativeApproval.approvalId());

        return checkpoint;
    }

    /**
     * 幂等保存和条件更新。
     * <p>
     * - 相同暂停现场：幂等返回已有 Checkpoint
     * - 不同暂停现场但已有 SUSPENDED：返回冲突
     * - 已有 RESUMING：使用 updateIfVersionMatches 更新回 SUSPENDED
     */
    private AgentCheckpoint handleExistingCheckpoint(
            AgentCheckpoint existingCp,
            SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren,
            PendingApproval representativeApproval,
            RunContext parentContext,
            SupervisorDefinition definition) {

        // 幂等：相同 approvalId 和 dispatchIndex
        if (isSameSuspendSite(existingCp, suspendedChildren)) {
            log.info("Supervisor checkpoint idempotent: runId={}, checkpointId={}",
                    parentContext.runId(), existingCp.checkpointId());
            return existingCp;
        }

        // 已有 SUSPENDED 且不同审批：冲突
        if (existingCp.status() == CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Supervisor checkpoint already SUSPENDED with different approval: runId="
                            + parentContext.runId());
        }

        // 已有 RESUMING：使用 updateIfVersionMatches 更新回 SUSPENDED
        if (existingCp.status() == CheckpointStatus.RESUMING) {
            return updateResumingToSuspended(existingCp, state, dispatchTasks,
                    suspendedChildren, representativeApproval, parentContext, definition);
        }

        // 其他状态不允许
        throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                "Supervisor checkpoint in unexpected status: " + existingCp.status()
                        + ", runId=" + parentContext.runId());
    }

    /**
     * 判断是否为相同暂停现场。
     * <p>
     * 比较 approvalId、childRunId、dispatchBatchId、dispatchIndex。
     */
    private boolean isSameSuspendSite(
            AgentCheckpoint existingCp,
            List<SupervisorChildExecution> newSuspendedChildren) {

        @SuppressWarnings("unchecked")
        List<SupervisorChildExecution> existingSuspended =
                (List<SupervisorChildExecution>) existingCp.stateData().get(SupervisorStateKeys.SUSPENDED_CHILDREN);
        if (existingSuspended == null || existingSuspended.size() != newSuspendedChildren.size()) {
            return false;
        }

        // 按 dispatchIndex 排序后比较
        List<SupervisorChildExecution> sortedExisting = existingSuspended.stream()
                .sorted(Comparator.comparingInt(SupervisorChildExecution::dispatchIndex))
                .toList();
        List<SupervisorChildExecution> sortedNew = newSuspendedChildren.stream()
                .sorted(Comparator.comparingInt(SupervisorChildExecution::dispatchIndex))
                .toList();

        for (int i = 0; i < sortedExisting.size(); i++) {
            SupervisorChildExecution existing = sortedExisting.get(i);
            SupervisorChildExecution newExec = sortedNew.get(i);

            if (!existing.approvalId().equals(newExec.approvalId())) {
                return false;
            }
            if (!existing.runLink().childRunId().equals(newExec.runLink().childRunId())) {
                return false;
            }
            if (!existing.runLink().dispatchBatchId().equals(newExec.runLink().dispatchBatchId())) {
                return false;
            }
            if (existing.dispatchIndex() != newExec.dispatchIndex()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 更新 RESUMING → SUSPENDED（再次暂停）。
     * <p>
     * 保持原 checkpointId，version + 1，新审批 ID 必须不同。
     * 版本冲突返回 CHECKPOINT_CONFLICT。
     */
    private AgentCheckpoint updateResumingToSuspended(
            AgentCheckpoint existingCp,
            SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren,
            PendingApproval representativeApproval,
            RunContext parentContext,
            SupervisorDefinition definition) {

        String checkpointId = existingCp.checkpointId();
        long expectedVersion = existingCp.version();
        Instant now = clock.instant();

        // 使用 Mapper 创建父 stateData
        Map<String, Object> stateData = stateMapper.toStateData(
                state, dispatchTasks, suspendedChildren, checkpointId);

        AgentCheckpoint updatedCheckpoint = new AgentCheckpoint(
                checkpointId,
                existingCp.runId(),
                existingCp.threadId(),
                existingCp.userId(),
                existingCp.executionType(),
                existingCp.purpose(),
                existingCp.agentName(),
                SupervisorNodeNames.DISPATCH_AGENTS,
                stateData,
                representativeApproval,
                CheckpointStatus.SUSPENDED,
                expectedVersion + 1,
                existingCp.createdAt(),
                now
        );

        // 12. 调用 SupervisorCheckpointValidator
        supervisorCheckpointValidator.validate(updatedCheckpoint);

        // 13. 条件更新
        boolean success = checkpointStore.updateIfVersionMatches(updatedCheckpoint, expectedVersion);
        if (!success) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Supervisor checkpoint version conflict during re-suspension: runId=" + parentContext.runId());
        }

        log.info("Supervisor checkpoint re-suspended: checkpointId={}, runId={}, version={}, newApprovalId={}",
                checkpointId, parentContext.runId(), expectedVersion + 1, representativeApproval.approvalId());

        return updatedCheckpoint;
    }
}
