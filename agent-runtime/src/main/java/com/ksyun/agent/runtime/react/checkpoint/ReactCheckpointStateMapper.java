package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.context.ContextProcessingTrace;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
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
 * Phase7 Batch4 新增：
 * - Checkpoint 必须保存并恢复 CONTEXT_WINDOW_SNAPSHOT 和 LATEST_CONTEXT_TRACE
 * - 中断前已经生成的摘要窗口必须保存
 * - 恢复后不得重新从完整历史摘要相同旧消息
 * - 恢复后 ToolExecution 继续使用原逻辑
 * - Observe 之后再次进入 Reason 时，窗口只吸收新增 ToolResult
 * - ContextWindowSnapshot 必须以不可变领域对象保存
 * - 旧 Checkpoint 缺少窗口字段时按无 Snapshot 首次处理，不得伪造错误的 consumed 数量
 */
public class ReactCheckpointStateMapper {

    /**
     * 从 ReactAgentState 生成 stateData 快照。
     * <p>
     * 直接使用 state.data()，因为 AgentCheckpoint 构造器已做深拷贝。
     * CONTEXT_WINDOW_SNAPSHOT 和 LATEST_CONTEXT_TRACE 作为不可变领域对象保存。
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
     * Phase7 Batch4：
     * - CONTEXT_WINDOW_SNAPSHOT 和 LATEST_CONTEXT_TRACE 保持（不清空）
     * - 旧 Checkpoint 缺少窗口字段时不伪造，按无 Snapshot 首次处理
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

        // CONTEXT_WINDOW_SNAPSHOT 和 LATEST_CONTEXT_TRACE 保持（如果存在）
        // 旧 Checkpoint 缺少窗口字段时，不伪造错误的 consumed 数量
        // 恢复后 Reason 进入时 previousSnapshot 为空，按首次处理
        // 不需要额外处理，保持 stateData 中的值即可（可能为 null）

        // RunContext 必须存在且类型正确（Validator 已在抢占前校验）
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

        return new ReactAgentState(resumeState);
    }
}
