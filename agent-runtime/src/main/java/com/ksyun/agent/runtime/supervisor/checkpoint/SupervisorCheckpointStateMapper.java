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
import com.ksyun.agent.runtime.context.ContextWindowSnapshot;
import com.ksyun.agent.runtime.context.ContextWindowSnapshotRestorer;
import com.ksyun.agent.runtime.supervisor.SupervisorAgentState;
import com.ksyun.agent.runtime.supervisor.SupervisorPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.CHECKPOINT_ID;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.CONTEXT_WINDOW_SNAPSHOT;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.DISPATCH_TASKS;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.FAILURE_ERROR_CODE;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.FAILURE_MESSAGE;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.FINAL_RESULT;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.RUN_CONTEXT;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.RUN_STATUS;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.STOP_REASON;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.SUPERVISOR_DEFINITION;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.SUPERVISOR_MESSAGES;
import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.SUSPENDED_CHILDREN;

/**
 * SupervisorAgentState 与 Checkpoint stateData 之间的映射器，纯 Java 实现。
 *
 * <p>职责：</p>
 *
 * <ol>
 *     <li>从 SupervisorAgentState + 本轮最新值生成不可变 stateData 快照</li>
 *     <li>从 AgentCheckpoint 重建 SupervisorAgentState（第四步恢复使用）</li>
 *     <li>集中处理类型校验</li>
 *     <li>不修改传入的 SupervisorAgentState</li>
 *     <li>不修改传入的任务列表</li>
 *     <li>不访问 CheckpointStore</li>
 *     <li>不生成 ID</li>
 *     <li>不调用模型、工具或子 Agent</li>
 * </ol>
 *
 * <p>不保存 CompiledGraph、Gateway、Registry、Spring Bean、异常对象、
 * HTTP Request/Response、密码、API Key、Token。</p>
 */
public class SupervisorCheckpointStateMapper {

    private static final Logger log =
            LoggerFactory.getLogger(SupervisorCheckpointStateMapper.class);

    private final SupervisorPromptBuilder promptBuilder;

    public SupervisorCheckpointStateMapper(
            SupervisorPromptBuilder promptBuilder
    ) {
        this.promptBuilder = Objects.requireNonNull(
                promptBuilder,
                "promptBuilder must not be null"
        );
    }

    /**
     * 从 SupervisorAgentState 生成本轮暂停快照。
     *
     * <p>使用白名单 payload，只保存恢复必需的 key。
     * dispatchTasks 和 suspendedChildren 由调用方提供本轮最新值。</p>
     *
     * <p>排除可重建对象：RunContext、SupervisorDefinition。
     * 排除恢复元数据：PENDING_APPROVAL、RUN_STATUS、STOP_REASON、
     * CHECKPOINT_ID（由 Checkpoint 顶层提供）。</p>
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
            String checkpointId
    ) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(
                dispatchTasks,
                "dispatchTasks must not be null"
        );
        Objects.requireNonNull(
                suspendedChildren,
                "suspendedChildren must not be null"
        );

        Map<String, Object> stateData = new LinkedHashMap<>(
                SupervisorPayloadBuilder.buildWhitelistPayload(state)
        );

        stateData.put(
                DISPATCH_TASKS,
                List.copyOf(dispatchTasks)
        );
        stateData.put(
                SUSPENDED_CHILDREN,
                List.copyOf(suspendedChildren)
        );

        return Collections.unmodifiableMap(stateData);
    }

    /**
     * 从 AgentCheckpoint 重建用于恢复执行的 SupervisorAgentState。
     *
     * <p>当前身份与定义由调用方安全重建后传入，不从 payload 读取旧的
     * RunContext 或 SupervisorDefinition。</p>
     *
     * <p>恢复时重新使用当前 SupervisorDefinition 和
     * SupervisorPromptBuilder 构建完整 Supervisor System Prompt，
     * 包括成员 Agent、JSON 决策协议和调度约束。</p>
     *
     * @param checkpoint 包含 Supervisor 暂停快照的 Checkpoint
     * @param definition 当前从 SupervisorRegistry 解析的 SupervisorDefinition
     * @param runContext 当前从 UserSession 重建的 RunContext
     * @return 可用于恢复执行的 SupervisorAgentState
     * @throws AgentFrameworkException 校验失败
     */
    public SupervisorAgentState fromCheckpointForResume(
            AgentCheckpoint checkpoint,
            SupervisorDefinition definition,
            RunContext runContext
    ) {
        Objects.requireNonNull(
                checkpoint,
                "checkpoint must not be null"
        );
        Objects.requireNonNull(
                definition,
                "definition must not be null"
        );
        Objects.requireNonNull(
                runContext,
                "runContext must not be null"
        );

        if (checkpoint.executionType()
                != CheckpointExecutionType.SUPERVISOR) {

            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint executionType must be SUPERVISOR, got "
                            + checkpoint.executionType()
            );
        }

        if (checkpoint.purpose()
                != CheckpointPurpose.HITL_RECOVERY) {

            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint purpose must be HITL_RECOVERY, got "
                            + checkpoint.purpose()
            );
        }

        Map<String, Object> stateData = checkpoint.stateData();

        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData must not be empty"
            );
        }

        if (!definition.name().equals(checkpoint.agentName())) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "SupervisorDefinition name does not match "
                            + "checkpoint agentName"
            );
        }

        /*
         * 创建独立状态副本，后续恢复覆盖不会修改
         * CheckpointStore 返回的原始 stateData。
         */
        Map<String, Object> resumeState =
                new HashMap<>(stateData);

        /*
         * Definition 和 RunContext 不从数据库 payload 恢复。
         * 使用当前 Registry 中的 Definition 和当前已认证用户构造的
         * RunContext。
         */
        resumeState.put(
                SUPERVISOR_DEFINITION,
                definition
        );
        resumeState.put(
                RUN_CONTEXT,
                runContext
        );

        /*
         * 必须使用 SupervisorPromptBuilder 构建完整的 Supervisor Prompt。
         *
         * 不能只使用 definition.systemPrompt()，因为完整 Prompt 还包含：
         * 1. 可用成员 Agent；
         * 2. DISPATCH / FINISH JSON 协议；
         * 3. 字段约束；
         * 4. 决策示例；
         * 5. 调度规则。
         */
        String systemPrompt = promptBuilder.build(definition);

        resumeState.put(
                SUPERVISOR_MESSAGES,
                rebuildSupervisorMessages(
                        systemPrompt,
                        stateData
                )
        );

        /*
         * Checkpoint 中不会持久化旧 SystemAgentMessage 和
         * MemoryContextAgentMessage。
         *
         * HITL 恢复时使用当前完整 Supervisor Prompt 重建 System 消息，
         * 同时修正 ContextWindowSnapshot 中的消息和 consumed 数量。
         */
        Object rawSnapshot =
                stateData.get(CONTEXT_WINDOW_SNAPSHOT);

        if (rawSnapshot instanceof ContextWindowSnapshot snapshot) {
            resumeState.put(
                    CONTEXT_WINDOW_SNAPSHOT,
                    ContextWindowSnapshotRestorer.forHitlResume(
                            snapshot,
                            systemPrompt
                    )
            );
        }

        resumeState.put(
                RUN_STATUS,
                RunStatus.RUNNING
        );
        resumeState.put(
                CHECKPOINT_ID,
                checkpoint.checkpointId()
        );

        resumeState.put(
                FINAL_RESULT,
                null
        );
        resumeState.put(
                FAILURE_ERROR_CODE,
                null
        );
        resumeState.put(
                FAILURE_MESSAGE,
                null
        );

        /*
         * 清除旧的暂停或失败停止原因，防止恢复后的 State
         * 再次被路由为已经终止的 SUSPENDED 状态。
         */
        resumeState.put(
                STOP_REASON,
                null
        );

        return new SupervisorAgentState(resumeState);
    }

    /**
     * 使用当前完整 Supervisor System Prompt 重建消息列表。
     *
     * <p>持久化 payload 中的 supervisorMessages 已经过安全过滤，
     * 不包含旧的 SystemAgentMessage 和 MemoryContextAgentMessage。</p>
     *
     * <p>恢复时先添加当前 SupervisorPromptBuilder 生成的 System 消息，
     * 再追加持久化的非 System 历史，避免重复插入旧协议。</p>
     *
     * @param systemPrompt 当前完整 Supervisor System Prompt
     * @param stateData    Checkpoint 中的安全状态数据
     * @return 不可变的 Supervisor 消息列表
     */
    private List<AgentMessage> rebuildSupervisorMessages(
            String systemPrompt,
            Map<String, Object> stateData
    ) {
        Object rawMessages =
                stateData.get(SUPERVISOR_MESSAGES);

        if (rawMessages == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Checkpoint stateData has no supervisorMessages"
            );
        }

        if (!(rawMessages instanceof List<?> list)) {
            throw new AgentFrameworkException(
                    AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "supervisorMessages has wrong type: expected List"
            );
        }

        List<AgentMessage> rebuilt =
                new ArrayList<>(list.size() + 1);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            rebuilt.add(
                    new SystemAgentMessage(systemPrompt)
            );
        }

        for (Object item : list) {
            if (item instanceof AgentMessage message) {
                rebuilt.add(message);
            }
        }

        return List.copyOf(rebuilt);
    }
}