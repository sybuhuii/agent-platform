package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.PendingApproval;
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
 * 恢复覆盖规则：
 * - pendingApproval 替换为 checkpoint 中已决策版本
 * - currentStatus 改为 RUNNING
 * - finalResult 清空
 * - stopReason 清空
 * - failureMessage 清空
 * - failureErrorCode 清空
 * - checkpointId 保持当前 Checkpoint ID
 * - pendingToolCalls、cursor、buffer、messages、iteration、maxIterations 保持
 * - RunContext 保持（不修改 Store 内 stateData）
 * - 不得重新执行 Reason
 * - 不得把 Checkpoint 状态直接作为可变 Map 交给图
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
        // AgentCheckpoint 构造器会做防御性深拷贝，此处直接传原 data
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
     * - 传入独立不可变快照，不修改 Store 内的 stateData
     * - RunContext 中 userId、runId、threadId 以 Checkpoint 顶层为准
     * <p>
     * 不信任 stateData 中与顶层 Checkpoint 冲突的身份字段。
     *
     * @param checkpoint 包含最新已决策 PendingApproval 的 Checkpoint
     * @return 可用于恢复执行的 ReactAgentState
     */
    public ReactAgentState fromCheckpointForResume(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
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

        // 校验并修复 RunContext 身份字段以 Checkpoint 顶层为准
        Object runContextObj = resumeState.get(RUN_CONTEXT);
        if (runContextObj instanceof RunContext rc) {
            if (!rc.runId().equals(checkpoint.runId())
                    || !rc.threadId().equals(checkpoint.threadId())
                    || !rc.userId().equals(checkpoint.userId())) {
                // 使用 Checkpoint 顶层身份重建 RunContext
                RunContext corrected = new RunContext(
                        checkpoint.userId(),
                        rc.sessionId(),
                        checkpoint.threadId(),
                        checkpoint.runId(),
                        rc.roles(),
                        rc.permissions()
                );
                resumeState.put(RUN_CONTEXT, corrected);
            }
        }

        return new ReactAgentState(resumeState);
    }
}
