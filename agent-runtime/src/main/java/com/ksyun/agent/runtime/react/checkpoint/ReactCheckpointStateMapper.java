package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.runtime.react.ReactAgentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReactAgentState 与 Checkpoint stateData 之间的映射器，纯 Java 实现。
 * <p>
 * 职责：
 * 1. 从 ReactAgentState 生成不可变白名单 stateData 快照（委托 CheckpointPayloadBuilder）
 * 2. 从 AgentCheckpoint 重建 ReactAgentState（注入当前 Definition / RunContext / System 消息）
 * 3. 集中处理类型校验
 * 4. 不得依赖 Spring
 * 5. 不得调用模型或工具
 * 6. 不得访问 CheckpointStore
 * 7. 不得生成新的 runId 或 threadId
 * <p>
 * 持久化安全：payload 不含 RunContext、AgentDefinition、SystemAgentMessage。
 * 恢复时由调用方传入当前 AgentRegistry 解析的 Definition 与当前 UserSession 重建的 RunContext。
 * System 消息根据当前 Definition 重新创建一条，再追加持久化的非 System 历史。
 */
public class ReactCheckpointStateMapper {

    /**
     * 从 ReactAgentState 生成白名单 stateData 快照。
     * <p>
     * 委托 CheckpointPayloadBuilder 只提取恢复必需 key，
     * 排除 RunContext、AgentDefinition、System 消息等可重建对象。
     *
     * @param state 当前 ReactAgentState
     * @return 不可变白名单快照
     */
    public Map<String, Object> toStateData(ReactAgentState state) {
        Objects.requireNonNull(state, "state must not be null");
        return CheckpointPayloadBuilder.buildWhitelistPayload(state);
    }

    /**
     * 从 AgentCheckpoint 重建用于恢复执行的 ReactAgentState。
     * <p>
     * 当前身份与定义由调用方安全重建后传入，不从 payload 读取旧 RunContext/Definition。
     * <p>
     * 恢复覆盖规则：
     * - 注入当前 AgentDefinition（来自 AgentRegistry）
     * - 注入当前 RunContext（来自当前 UserSession，runId/threadId 取 Checkpoint 顶层）
     * - messages：根据当前 Definition 重新创建一条 System 消息，再追加持久化的非 System 历史
     * - pendingApproval 替换为 checkpoint 中已决策的版本
     * - RunStatus 改为 RUNNING
     * - finalResult / stopReason / failureMessage / failureErrorCode 清空
     * - checkpointId 取 Checkpoint 顶层
     * - 传入独立不可变快照，不修改 Store 内的 stateData
     *
     * @param checkpoint  包含最新已决策 PendingApproval 的 Checkpoint
     * @param definition  当前从 AgentRegistry 解析的 AgentDefinition
     * @param runContext  当前从 UserSession 重建的 RunContext
     * @return 可用于恢复执行的 ReactAgentState
     * @throws AgentFrameworkException stateData 缺失或类型错误
     */
    public ReactAgentState fromCheckpointForResume(
            AgentCheckpoint checkpoint,
            AgentDefinition definition,
            RunContext runContext) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(runContext, "runContext must not be null");

        Map<String, Object> stateData = checkpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData must not be empty");
        }

        // 校验 Definition 名称与 Checkpoint agentName 一致
        if (!definition.name().equals(checkpoint.agentName())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Definition name does not match checkpoint agentName");
        }

        // 构造独立不可变快照，不修改 Store 内的 stateData
        Map<String, Object> resumeState = new HashMap<>(stateData);

        // 注入当前身份与定义（payload 中不持久化这些）
        resumeState.put(AGENT_DEFINITION, definition);
        resumeState.put(RUN_CONTEXT, runContext);

        // 重建 System 消息：根据当前 Definition 重新创建一条，
        // 再追加持久化的非 System 历史（payload 已过滤 System/MemoryContext）
        resumeState.put(MESSAGES, rebuildMessages(definition, stateData));

        // 恢复覆盖
        resumeState.put(PENDING_APPROVAL, checkpoint.pendingApproval()); // 已决策版本
        resumeState.put(RUN_STATUS, RunStatus.RUNNING);
        resumeState.put(FINAL_RESULT, null);
        resumeState.put(STOP_REASON, null);
        resumeState.put(FAILURE_MESSAGE, null);
        resumeState.put(FAILURE_ERROR_CODE, null);
        resumeState.put(CHECKPOINT_ID, checkpoint.checkpointId());

        // CONTEXT_WINDOW_SNAPSHOT 和 LATEST_CONTEXT_TRACE 保持（如果存在）

        return new ReactAgentState(resumeState);
    }

    /**
     * 根据当前 Definition 重新创建一条 System 消息，再追加持久化的非 System 历史。
     * <p>
     * payload 中的 messages 已过滤 SystemAgentMessage 和 MemoryContextAgentMessage。
     * 恢复时用当前 systemPrompt 重建 System 消息（Definition 无 systemPrompt 时跳过），
     * 不重复插入。
     */
    private List<AgentMessage> rebuildMessages(AgentDefinition definition, Map<String, Object> stateData) {
        Object rawMessages = stateData.get(MESSAGES);
        if (rawMessages == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData has no messages");
        }
        if (!(rawMessages instanceof List<?> list)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "messages has wrong type: expected List");
        }

        List<AgentMessage> rebuilt = new ArrayList<>(list.size() + 1);
        if (definition.systemPrompt() != null && !definition.systemPrompt().isBlank()) {
            rebuilt.add(new SystemAgentMessage(definition.systemPrompt()));
        }
        for (Object item : list) {
            if (item instanceof AgentMessage msg) {
                rebuilt.add(msg);
            }
        }
        return List.copyOf(rebuilt);
    }
}
