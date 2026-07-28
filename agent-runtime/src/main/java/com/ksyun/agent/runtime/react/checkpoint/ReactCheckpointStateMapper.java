package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.runtime.react.ReactAgentState;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReactAgentState 与 Checkpoint stateData 之间的映射器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 从 ReactAgentState 生成不可变 stateData 快照
 * 2. 从 AgentCheckpoint 重建 ReactAgentState
 * 3. 集中处理类型校验
 * 4. 集中处理缺失字段
 * 5. 不得依赖 Spring
 * 6. 不得调用模型或工具
 * 7. 不得访问 CheckpointStore
 * 8. 不得生成新的 runId 或 threadId
 * <p>
 * 恢复覆盖规则（Validator 已在抢占前拒绝身份冲突）：
 * - pendingApproval 替换为 checkpoint 中已决策版本
 * - currentStatus 改为 RUNNING
 * - finalResult 清空
 * - stopReason 清空
 * - failureMessage 清空
 * - failureErrorCode 清空
 * - checkpointId 保持当前 Checkpoint ID
 * - RunContext 保持（Validator已校验与顶层一致，不修正）
 * - messages、iteration、maxIterations、pendingToolCalls、cursor、buffer 保持
 * - 不修改 Store 内 stateData
 * - 不得信任或修正冲突的身份字段后继续恢复
 * - RunContext 缺失或类型错误时抛结构化异常
 * - 不重新进入产生当前 ToolCall 的 Reason
 */
public class ReactCheckpointStateMapper {

    /**
     * 从 ReactAgentState 生成 stateData 快照。
     * <p>
     * 直接使用 state.data()，因为 AgentCheckpoint 构造器已做深拷贝。
     *
     * @param state 当前 ReactAgentState
     * @return 不可变快照
     */
    public Map<String, Object> toStateData(ReactAgentState state) {
        Objects.requireNonNull(state, "state must not be null");
        return new HashMap<>(state.data());
    }

    /**
     * 从 AgentCheckpoint 重建用于恢复执行的 ReactAgentState。
     * <p>
     * 恢复覆盖规则：
     * - pendingApproval 替换为 checkpoint 中已决策的版本
     * - RunStatus 改为 RUNNING
     * - finalResult 清空
     * - stopReason 清空
     * - failureMessage 清空
     * - failureErrorCode 清空
     * - checkpointId 保持当前 Checkpoint ID
     * - RunContext 保持（Validator已校验与顶层一致，不修正冲突）
     * - 传入独立不可变快照，不修改 Store 内的 stateData
     * <p>
     * RunContext 缺失或类型错误时抛 CHECKPOINT_NOT_RESUMABLE，
     * 不修正后继续恢复。
     *
     * @param checkpoint 包含最新已决策 PendingApproval 的 Checkpoint
     * @return 可用于恢复执行的 ReactAgentState
     * @throws AgentFrameworkException RunContext 缺失或类型错误
     */
    public ReactAgentState fromCheckpointForResume(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData must not be empty");
        }

        // 构造独立不可变快照，不修改 Store 内的 stateData
        Map<String, Object> resumeState = new HashMap<>(stateData);

        // 恢复覆盖
        resumeState.put(PENDING_APPROVAL, checkpoint.pendingApproval()); // 已决策版本
        resumeState.put(RUN_STATUS, RunStatus.RUNNING);
        resumeState.put(FINAL_RESULT, null);
        resumeState.put(STOP_REASON, null);
        resumeState.put(FAILURE_MESSAGE, null);
        resumeState.put(FAILURE_ERROR_CODE, null);
        resumeState.put(CHECKPOINT_ID, checkpoint.checkpointId());

        // RunContext 必须存在且类型正确（Validator 已在抢占前校验）
        // 此处做防御性检查：缺失或类型错误直接抛异常，不修正后继续
        Object runContextObj = resumeState.get(RUN_CONTEXT);
        if (runContextObj == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "RunContext is missing in stateData");
        }
        if (!(runContextObj instanceof RunContext)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "RunContext has wrong type: expected RunContext, got "
                            + runContextObj.getClass().getName());
        }

        // Validator 已校验 RunContext 身份字段与 Checkpoint 顶层一致
        // Mapper 不修正冲突的身份字段，直接使用 stateData 中的 RunContext

        return new ReactAgentState(resumeState);
    }
}
