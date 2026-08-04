package com.ksyun.agent.runtime.supervisor.checkpoint;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.message.AgentMessage;
import com.ksyun.agent.core.message.SystemAgentMessage;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * 使用白名单 payload，只保存恢复必需的 key。
     * dispatchTasks 和 suspendedChildren 由调用方提供本轮最新值。
     * <p>
     * 排除可重建对象：RunContext、SupervisorDefinition。
     * 排除恢复元数据：PENDING_APPROVAL、RUN_STATUS、STOP_REASON、CHECKPOINT_ID（由 Checkpoint 顶层提供）。
     *
     * @param state             当前 SupervisorAgentState
     * @param dispatchTasks     本轮最新 DISPATCH_TASKS
     * @param suspendedChildren 本轮最新 SUSPENDED_CHILDREN
     * @param checkpointId      当前父 checkpointId（不进入 payload，用于日志）
     * @return 不可变白名单快照
     */
    public Map<String, Object> toStateData(
            SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren,
            String checkpointId) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(dispatchTasks, "dispatchTasks must not be null");
        Objects.requireNonNull(suspendedChildren, "suspendedChildren must not be null");

        // 使用白名单 payload 基础
        Map<String, Object> stateData = new LinkedHashMap<>(SupervisorPayloadBuilder.buildWhitelistPayload(state));

        // 用本轮最新值覆盖 dispatchTasks 和 suspendedChildren
        stateData.put(DISPATCH_TASKS, List.copyOf(dispatchTasks));
        stateData.put(SUSPENDED_CHILDREN, List.copyOf(suspendedChildren));

        return Collections.unmodifiableMap(stateData);
    }

    /**
     * 从 AgentCheckpoint 重建用于恢复执行的 SupervisorAgentState。
     * <p>
     * 当前身份与定义由调用方安全重建后传入，不从 payload 读取旧 RunContext/Definition。
     * <p>
     * 恢复映射必须：
     * 1. 校验 executionType == SUPERVISOR
     * 2. 校验 purpose == HITL_RECOVERY
     * 3. 校验 stateData 非空
     * 4. 校验 definition.name() 与 checkpoint.agentName() 一致
     * 5. 复制 stateData，不得修改 Store 中的 Map
     * 6. 注入当前 SupervisorDefinition（来自 SupervisorRegistry）
     * 7. 注入当前 RunContext（来自当前 UserSession，runId/threadId 取 Checkpoint 顶层）
     * 8. 重建 supervisorMessages：根据当前 Definition 重新创建一条 System 消息，
     *    再追加持久化的非 System 历史
     * 9. 保留：当前决策、待分派任务、子任务执行状态表、
     *    已完成子任务结果、未开始子任务、暂停子任务引用、iteration、上下文窗口状态
     * 10. 重置：FINAL_RESULT = null, FAILURE_ERROR_CODE = null, FAILURE_MESSAGE = null
     * 11. 设置：RUN_STATUS = RUNNING, CHECKPOINT_ID = checkpoint.checkpointId()
     * 12. 不恢复旧 UserSession
     * 13. 不伪造新的 runId、threadId
     * 14. 不执行子 Agent
     * 15. 不执行 Supervisor 模型
     * <p>
     * 恢复后 State 不能继续被路由为已终止的 SUSPENDED。
     *
     * @param checkpoint  包含 Supervisor 暂停快照的 Checkpoint
     * @param definition  当前从 SupervisorRegistry 解析的 SupervisorDefinition
     * @param runContext  当前从 UserSession 重建的 RunContext
     * @return 可用于恢复执行的 SupervisorAgentState
     * @throws AgentFrameworkException 校验失败
     */
    public SupervisorAgentState fromCheckpointForResume(
            AgentCheckpoint checkpoint,
            SupervisorDefinition definition,
            RunContext runContext) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(runContext, "runContext must not be null");

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

        // 校验 Definition 名称与 Checkpoint agentName 一致
        if (!definition.name().equals(checkpoint.agentName())) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "SupervisorDefinition name does not match checkpoint agentName");
        }

        // 构造独立不可变快照，不修改 Store 内的 stateData
        Map<String, Object> resumeState = new HashMap<>(stateData);

        // 注入当前身份与定义（payload 中不持久化这些）
        resumeState.put(SUPERVISOR_DEFINITION, definition);
        resumeState.put(RUN_CONTEXT, runContext);

        // 重建 supervisorMessages：根据当前 Definition 重新创建一条 System 消息，
        // 再追加持久化的非 System 历史（payload 已过滤 System/MemoryContext）
        resumeState.put(SUPERVISOR_MESSAGES, rebuildSupervisorMessages(definition, stateData));

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

    /**
     * 根据当前 Definition 重新创建一条 System 消息，再追加持久化的非 System 历史。
     * <p>
     * payload 中的 supervisorMessages 已过滤 SystemAgentMessage 和 MemoryContextAgentMessage。
     * 恢复时用当前 systemPrompt 重建 System 消息（Definition 无 systemPrompt 时跳过），
     * 不重复插入。
     */
    private List<AgentMessage> rebuildSupervisorMessages(
            SupervisorDefinition definition,
            Map<String, Object> stateData) {
        Object rawMessages = stateData.get(SUPERVISOR_MESSAGES);
        if (rawMessages == null) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData has no supervisorMessages");
        }
        if (!(rawMessages instanceof List<?> list)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "supervisorMessages has wrong type: expected List");
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
