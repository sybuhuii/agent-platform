package com.ksyun.agent.runtime.supervisor.checkpoint;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorChildExecutionStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildRunLink;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;
import com.ksyun.agent.runtime.supervisor.SupervisorNodeNames;
import com.ksyun.agent.runtime.supervisor.SupervisorStateKeys;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Supervisor Checkpoint 校验器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 先调用现有通用 CheckpointValidator
 * 2. 再执行 Supervisor 专属校验
 * 3. 不修改 Checkpoint
 * 4. 不访问 Store
 * 5. 不调用模型或工具
 * <p>
 * 不把完整 State 或消息正文写入错误信息。
 */
public class SupervisorCheckpointValidator {

    private final CheckpointValidator checkpointValidator;

    public SupervisorCheckpointValidator(CheckpointValidator checkpointValidator) {
        this.checkpointValidator = Objects.requireNonNull(checkpointValidator,
                "checkpointValidator must not be null");
    }

    /**
     * 校验 Supervisor Checkpoint。
     * <p>
     * 先调用通用校验，再执行 Supervisor 专属校验。
     *
     * @param checkpoint 待校验的 Checkpoint
     * @throws AgentFrameworkException 校验失败
     */
    public void validate(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        // 1. 通用校验
        checkpointValidator.validate(checkpoint);

        // 2. Supervisor 专属校验
        validateSupervisorSpecific(checkpoint);
    }

    private void validateSupervisorSpecific(AgentCheckpoint checkpoint) {
        // executionType == SUPERVISOR
        if (checkpoint.executionType() != CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint executionType must be SUPERVISOR, got " + checkpoint.executionType());
        }

        // purpose == HITL_RECOVERY
        if (checkpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint purpose must be HITL_RECOVERY, got " + checkpoint.purpose());
        }

        // status == SUSPENDED（保存时）
        if (checkpoint.status() != CheckpointStatus.SUSPENDED) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint status must be SUSPENDED, got " + checkpoint.status());
        }

        // nodeName == DISPATCH_AGENTS
        if (!SupervisorNodeNames.DISPATCH_AGENTS.equals(checkpoint.nodeName())) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint nodeName must be DISPATCH_AGENTS, got " + checkpoint.nodeName());
        }

        // stateData 非空
        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint stateData must not be empty");
        }

        // 注：RUN_STATUS / STOP_REASON / CHECKPOINT_ID 不再进入白名单 payload。
        // checkpoint.status()==SUSPENDED 已在上方校验，等价于保存时 runStatus==SUSPENDED。
        // CHECKPOINT_ID 来自 checkpoint 顶层，无需从 stateData 重复校验。

        // DISPATCH_TASKS 非空
        @SuppressWarnings("unchecked")
        List<SupervisorChildExecution> dispatchTasks =
                (List<SupervisorChildExecution>) stateData.get(SupervisorStateKeys.DISPATCH_TASKS);
        if (dispatchTasks == null || dispatchTasks.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint DISPATCH_TASKS must not be empty");
        }

        // SUSPENDED_CHILDREN 非空
        @SuppressWarnings("unchecked")
        List<SupervisorChildExecution> suspendedChildren =
                (List<SupervisorChildExecution>) stateData.get(SupervisorStateKeys.SUSPENDED_CHILDREN);
        if (suspendedChildren == null || suspendedChildren.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                    "Supervisor checkpoint SUSPENDED_CHILDREN must not be empty");
        }

        // 校验每个暂停子任务
        validateSuspendedChildren(suspendedChildren, dispatchTasks, checkpoint);
    }

    private void validateSuspendedChildren(
            List<SupervisorChildExecution> suspendedChildren,
            List<SupervisorChildExecution> dispatchTasks,
            AgentCheckpoint checkpoint) {

        Set<String> seenChildRunIds = new HashSet<>();
        Set<Integer> seenDispatchIndices = new HashSet<>();

        for (SupervisorChildExecution exec : suspendedChildren) {
            // status == SUSPENDED
            if (exec.status() != SupervisorChildExecutionStatus.SUSPENDED) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child must have status SUSPENDED, got " + exec.status());
            }

            // runLink 非空
            SupervisorChildRunLink runLink = exec.runLink();
            if (runLink == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child must have non-null runLink");
            }

            // result 非空
            if (exec.result() == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child must have non-null result");
            }

            // result.status == SUSPENDED
            if (exec.result().status() != RunStatus.SUSPENDED) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child result.status must be SUSPENDED");
            }

            // approvalId 非空
            if (exec.approvalId() == null || exec.approvalId().isBlank()) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child must have non-blank approvalId");
            }

            // runLink.parentRunId == parent checkpoint.runId
            if (!runLink.parentRunId().equals(checkpoint.runId())) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child runLink.parentRunId must match checkpoint.runId");
            }

            // runLink.parentThreadId == parent checkpoint.threadId
            if (!runLink.parentThreadId().equals(checkpoint.threadId())) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child runLink.parentThreadId must match checkpoint.threadId");
            }

            // runLink.childTaskId == execution.task.taskId
            if (!runLink.childTaskId().equals(exec.task().taskId())) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child runLink.childTaskId must match execution.task.taskId");
            }

            // runLink.dispatchIndex == execution.dispatchIndex
            if (runLink.dispatchIndex() != exec.dispatchIndex()) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child runLink.dispatchIndex must match execution.dispatchIndex");
            }

            // 不允许同一个 childRunId 出现两次
            if (!seenChildRunIds.add(runLink.childRunId())) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Duplicate childRunId in suspended children: " + runLink.childRunId());
            }

            // 不允许同一个 dispatchIndex 出现两次
            if (!seenDispatchIndices.add(exec.dispatchIndex())) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Duplicate dispatchIndex in suspended children: " + exec.dispatchIndex());
            }

            // SUSPENDED_CHILDREN 中的元素能在 DISPATCH_TASKS 中通过稳定标识找到
            boolean found = dispatchTasks.stream()
                    .anyMatch(dt -> dt.dispatchIndex() == exec.dispatchIndex()
                            && dt.task().taskId().equals(exec.task().taskId()));
            if (!found) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Suspended child not found in DISPATCH_TASKS: dispatchIndex=" + exec.dispatchIndex());
            }
        }

        // 已完成任务必须保留结果
        for (SupervisorChildExecution exec : dispatchTasks) {
            if ((exec.status() == SupervisorChildExecutionStatus.COMPLETED
                    || exec.status() == SupervisorChildExecutionStatus.FAILED)
                    && exec.result() == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "Completed/Failed child must have result: dispatchIndex=" + exec.dispatchIndex());
            }

            // NOT_STARTED 任务不得伪造结果
            if (exec.status() == SupervisorChildExecutionStatus.NOT_STARTED && exec.result() != null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "NOT_STARTED child must not have result: dispatchIndex=" + exec.dispatchIndex());
            }

            // 不能存在没有 Link 却标记为 SUSPENDED 的任务
            if (exec.status() == SupervisorChildExecutionStatus.SUSPENDED && exec.runLink() == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "SUSPENDED child must have runLink: dispatchIndex=" + exec.dispatchIndex());
            }
        }
    }
}
