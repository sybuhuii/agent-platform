package com.ksyun.agent.runtime.supervisor.checkpoint;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * SupervisorAgentState 与 Checkpoint stateData 之间的映射器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 从 SupervisorAgentState + 本轮最新值生成不可变 stateData 快照
 * 2. 从 AgentCheckpoint 重建 SupervisorAgentState（第四步恢复使用）
 * 3. 集中处理类型校验
 * 4. 不修改传入的 SupervisorAgentState
 * 5. 不修改传入的任务列表
 * 6. 不访问 CheckpointStore
 * 7. 不生成 ID
 * 8. 不调用模型、工具或子 Agent
 * <p>
 * 不保存 CompiledGraph、Gateway、Registry、Spring Bean、异常对象、
 * HTTP Request/Response、密码、API Key、Token。
 */
public class SupervisorCheckpointStateMapper {

    private static final Logger log = LoggerFactory.getLogger(SupervisorCheckpointStateMapper.class);

    /**
     * 从 SupervisorAgentState 生成本轮暂停快照。
     * <p>
     * 保存时强制覆盖：
     * - RUN_STATUS = SUSPENDED
     * - STOP_REASON = SUSPENDED
     * - CHECKPOINT_ID = 当前父 checkpointId
     * <p>
     * 使用本轮最新值覆盖旧 State 中的对应字段。
     * 不修改传入的 SupervisorAgentState。
     *
     * @param state             当前 SupervisorAgentState
     * @param dispatchTasks     本轮最新 DISPATCH_TASKS
     * @param suspendedChildren 本轮最新 SUSPENDED_CHILDREN
     * @param checkpointId      当前父 checkpointId
     * @return 不可变快照
     */
    public Map<String, Object> toStateData(
            SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren,
            String checkpointId) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(dispatchTasks, "dispatchTasks must not be null");
        Objects.requireNonNull(suspendedChildren, "suspendedChildren must not be null");

        // 复制当前 State
        Map<String, Object> stateData = new HashMap<>(state.data());

        // 用本轮最新值覆盖
        stateData.put(DISPATCH_TASKS, List.copyOf(dispatchTasks));
        stateData.put(SUSPENDED_CHILDREN, List.copyOf(suspendedChildren));

        // 强制覆盖保存时语义
        stateData.put(RUN_STATUS, RunStatus.SUSPENDED);
        stateData.put(STOP_REASON, SupervisorStopReason.SUSPENDED);
        stateData.put(CHECKPOINT_ID, checkpointId);

        return stateData;
    }

    /**
     * 从 AgentCheckpoint 重建用于恢复执行的 SupervisorAgentState。
     * <p>
     * 恢复映射必须：
     * 1. 校验 executionType == SUPERVISOR
     * 2. 校验 purpose == HITL_RECOVERY
     * 3. 校验 stateData 非空
     * 4. 复制 stateData，不得修改 Store 中的 Map
     * 5. 保留：Supervisor消息、当前决策、待分派任务、子任务执行状态表、
     *    已完成子任务结果、未开始子任务、暂停子任务引用、iteration、上下文窗口状态
     * 6. 重置：FINAL_RESULT = null, FAILURE_ERROR_CODE = null, FAILURE_MESSAGE = null
     * 7. 设置：RUN_STATUS = RUNNING, CHECKPOINT_ID = checkpoint.checkpointId()
     * 8. 不恢复旧 UserSession
     * 9. 不伪造新的 runId、threadId
     * 10. 不执行子 Agent
     * 11. 不执行 Supervisor 模型
     * <p>
     * 恢复后 State 不能继续被路由为已终止的 SUSPENDED。
     *
     * @param checkpoint 包含 Supervisor 暂停快照的 Checkpoint
     * @return 可用于恢复执行的 SupervisorAgentState
     * @throws AgentFrameworkException 校验失败
     */
    public SupervisorAgentState fromCheckpointForResume(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        // 校验 executionType == SUPERVISOR
        if (checkpoint.executionType() != CheckpointExecutionType.SUPERVISOR) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint executionType must be SUPERVISOR, got " + checkpoint.executionType());
        }

        // 校验 purpose == HITL_RECOVERY
        if (checkpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint purpose must be HITL_RECOVERY, got " + checkpoint.purpose());
        }

        // 校验 stateData 非空
        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData must not be empty");
        }

        // 构造独立不可变快照，不修改 Store 内的 stateData
        Map<String, Object> resumeState = new HashMap<>(stateData);

        // 恢复覆盖
        resumeState.put(RUN_STATUS, RunStatus.RUNNING);
        resumeState.put(CHECKPOINT_ID, checkpoint.checkpointId());

        // 重置终态字段
        resumeState.put(FINAL_RESULT, null);
        resumeState.put(FAILURE_ERROR_CODE, null);
        resumeState.put(FAILURE_MESSAGE, null);

        // STOP_REASON 清空，防止恢复后被路由为已终止的 SUSPENDED
        resumeState.put(STOP_REASON, null);

        return new SupervisorAgentState(resumeState);
    }
}
