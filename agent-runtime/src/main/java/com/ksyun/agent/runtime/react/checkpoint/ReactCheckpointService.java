package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.store.CheckpointIdGenerator;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.ReactAgentState;
import com.ksyun.agent.runtime.react.ReactStateKeys;
import com.ksyun.agent.runtime.react.checkpoint.validator.CheckpointValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ReAct Checkpoint 服务。
 * <p>
 * 依赖：CheckpointStore、CheckpointIdGenerator、CheckpointValidator、Clock
 * <p>
 * suspend 方法职责：
 * - approval 由 ToolApprovalInterceptor 创建，Service 不重新生成 approvalId
 * - 创建完整状态快照，并覆盖 cursor、buffer、pendingApproval、SUSPENDED
 * - executionType=REACT_AGENT
 * - nodeName 使用真实 execute_tools 节点名
 * - runId、threadId、userId 来自 RunContext
 * - 新 Checkpoint version=0
 * - status=CheckpointStatus.SUSPENDED
 * - 保存前调用 CheckpointValidator
 * - 使用 CheckpointStore.save
 * <p>
 * 多次挂起支持：
 * - 首次挂起：不存在 Checkpoint，创建 version=0，status=SUSPENDED
 * - 再次挂起：已存在同 runId Checkpoint（status=RESUMING）
 *   - 新 approvalId 与已有不同：使用 updateIfVersionMatches 更新
 *   - version 在已有基础上 +1
 *   - 如果 approvalId 相同：幂等返回已有 Checkpoint
 * - 不得无条件覆盖
 * - 不得用 LangGraph4j CheckpointSaver
 * - 不得从 SUSPENDED 状态覆盖（SUSPENDED 应该先被审批再恢复）
 * <p>
 * 不实现恢复。不删除旧 Checkpoint。
 * 不记录完整 stateData、参数或审批对象。
 * 纯 Java 实现，不依赖 Spring。
 */
public class ReactCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(ReactCheckpointService.class);

    private final CheckpointStore checkpointStore;
    private final CheckpointIdGenerator checkpointIdGenerator;
    private final CheckpointValidator checkpointValidator;
    private final Clock clock;

    public ReactCheckpointService(CheckpointStore checkpointStore,
                                   CheckpointIdGenerator checkpointIdGenerator,
                                   CheckpointValidator checkpointValidator,
                                   Clock clock) {
        this.checkpointStore = checkpointStore;
        this.checkpointIdGenerator = checkpointIdGenerator;
        this.checkpointValidator = checkpointValidator;
        this.clock = clock;
    }

    /**
     * 挂起运行，保存 Checkpoint。
     * <p>
     * 支持首次挂起和再次挂起。
     *
     * @param state     当前 ReactAgentState
     * @param nodeName  恢复节点名（如 execute_tools）
     * @param approval  由 ToolApprovalInterceptor 创建的审批记录
     * @param cursor    当前执行游标
     * @param buffer    已完成的 ToolResult 缓冲
     * @return 保存后的 Checkpoint
     */
    public AgentCheckpoint suspend(ReactAgentState state,
                                    String nodeName,
                                    PendingApproval approval,
                                    int cursor,
                                    List<com.ksyun.agent.core.tool.ToolResult> buffer) {
        RunContext runContext = ReactStateKeys.getRunContext(state);
        var definition = ReactStateKeys.getAgentDefinition(state);

        // 检查已存在 Checkpoint
        Optional<AgentCheckpoint> existing = checkpointStore.load(runContext.runId());

        if (existing.isPresent()) {
            return handleReSuspend(existing.get(), state, nodeName, approval, cursor, buffer, runContext, definition);
        }

        // 首次挂起：创建 version=0 的 Checkpoint
        return createNewCheckpoint(state, nodeName, approval, cursor, buffer, runContext, definition);
    }

    /**
     * 首次挂起：创建新 Checkpoint。
     */
    private AgentCheckpoint createNewCheckpoint(ReactAgentState state,
                                                 String nodeName,
                                                 PendingApproval approval,
                                                 int cursor,
                                                 List<com.ksyun.agent.core.tool.ToolResult> buffer,
                                                 RunContext runContext,
                                                 com.ksyun.agent.core.agent.AgentDefinition definition) {
        // 同一 approvalId 幂等检查
        Optional<AgentCheckpoint> existing = checkpointStore.load(runContext.runId());
        if (existing.isPresent()) {
            AgentCheckpoint existingCp = existing.get();
            if (existingCp.pendingApproval() != null
                    && existingCp.pendingApproval().approvalId().equals(approval.approvalId())) {
                log.info("Checkpoint already exists for runId={}, approvalId={}, returning existing",
                        runContext.runId(), approval.approvalId());
                return existingCp;
            }
            // 不同 approvalId 走再次挂起路径
            return handleReSuspend(existingCp, state, nodeName, approval, cursor, buffer, runContext, definition);
        }

        // 补全 approval 中的 agentName 和 nodeName
        PendingApproval filledApproval = fillApprovalContext(approval, definition.name(), nodeName);

        Map<String, Object> stateData = buildStateData(state, cursor, buffer);

        String checkpointId = checkpointIdGenerator.generate();
        Instant now = clock.instant();

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                checkpointId,
                runContext.runId(),
                runContext.threadId(),
                runContext.userId(),
                CheckpointExecutionType.REACT_AGENT,
                CheckpointPurpose.HITL_RECOVERY,
                definition.name(),
                nodeName,
                stateData,
                filledApproval,
                CheckpointStatus.SUSPENDED,
                0,
                now,
                now
        );

        checkpointValidator.validate(checkpoint);
        checkpointStore.save(checkpoint);

        log.info("Checkpoint saved: checkpointId={}, runId={}, version=0, approvalId={}",
                checkpointId, runContext.runId(), filledApproval.approvalId());

        return checkpoint;
    }

    /**
     * 再次挂起：更新已有 Checkpoint。
     * <p>
     * - 只能从 RESUMING 状态再次挂起（恢复后遇到新的危险工具）
     * - 新 approvalId 与已有不同：使用 updateIfVersionMatches
     * - version 在已有基础上 +1
     * - 新 checkpointId
     * - 如果 approvalId 相同：幂等返回已有 Checkpoint
     * - 不得从 SUSPENDED 状态覆盖（SUSPENDED 应该先审批再恢复）
     * - 不得删除旧 Checkpoint 后重新 save
     * - 不得复用旧 approvalId
     */
    private AgentCheckpoint handleReSuspend(AgentCheckpoint existingCp,
                                             ReactAgentState state,
                                             String nodeName,
                                             PendingApproval approval,
                                             int cursor,
                                             List<com.ksyun.agent.core.tool.ToolResult> buffer,
                                             RunContext runContext,
                                             com.ksyun.agent.core.agent.AgentDefinition definition) {
        // 相同 approvalId 幂等返回
        if (existingCp.pendingApproval() != null
                && existingCp.pendingApproval().approvalId().equals(approval.approvalId())) {
            log.info("Re-suspend with same approvalId, idempotent: runId={}, approvalId={}",
                    runContext.runId(), approval.approvalId());
            return existingCp;
        }

        // 只允许从 RESUMING 状态再次挂起
        if (existingCp.status() != CheckpointStatus.RESUMING) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Cannot re-suspend checkpoint in status " + existingCp.status()
                            + ", expected RESUMING. runId=" + runContext.runId());
        }

        // 新 approvalId：更新 Checkpoint
        // 补全 approval 中的 agentName 和 nodeName
        PendingApproval filledApproval = fillApprovalContext(approval, definition.name(), nodeName);

        Map<String, Object> stateData = buildStateData(state, cursor, buffer);

        // 保持原 checkpointId（同一runId整个恢复生命周期保持原checkpointId）
        long expectedVersion = existingCp.version();
        Instant now = clock.instant();

        AgentCheckpoint updatedCheckpoint = new AgentCheckpoint(
                existingCp.checkpointId(),  // 保持原 checkpointId，不生成新的
                existingCp.runId(),
                existingCp.threadId(),
                existingCp.userId(),
                existingCp.executionType(),
                existingCp.purpose(),       // HITL_RECOVERY 保持不变
                existingCp.agentName(),
                nodeName,
                stateData,
                filledApproval,
                CheckpointStatus.SUSPENDED,
                expectedVersion + 1,
                existingCp.createdAt(),
                now
        );

        checkpointValidator.validate(updatedCheckpoint);

        boolean success = checkpointStore.updateIfVersionMatches(updatedCheckpoint, expectedVersion);
        if (!success) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_CONFLICT,
                    "Checkpoint version conflict during re-suspension: runId=" + runContext.runId());
        }

        log.info("Checkpoint re-suspended: checkpointId={}, runId={}, version={}, newApprovalId={}",
                existingCp.checkpointId(), runContext.runId(), expectedVersion + 1, filledApproval.approvalId());

        return updatedCheckpoint;
    }

    /**
     * 构造白名单状态快照。
     * <p>
     * 只包含恢复所必需的 key（见 {@link CheckpointPayloadBuilder}），
     * 排除 RunContext、AgentDefinition 等可重建对象，
     * 以及 PENDING_APPROVAL/RUN_STATUS/STOP_REASON/CHECKPOINT_ID 等恢复元数据
     * （这些来自 Checkpoint 顶层，恢复时由 fromCheckpointForResume 注入）。
     * <p>
     * 仅 toolExecutionCursor 和 toolExecutionBuffer 使用传入参数覆盖当前 state 值，
     * 以反映中断时的真实执行位置。
     */
    private Map<String, Object> buildStateData(ReactAgentState state,
                                                 int cursor,
                                                 List<com.ksyun.agent.core.tool.ToolResult> buffer) {
        Map<String, Object> stateData = new java.util.LinkedHashMap<>(
                CheckpointPayloadBuilder.buildWhitelistPayload(state));
        // 覆盖为中断时的真实执行位置（恢复时从这里继续）
        stateData.put(ReactStateKeys.TOOL_EXECUTION_CURSOR, cursor);
        stateData.put(ReactStateKeys.TOOL_EXECUTION_BUFFER, buffer != null ? List.copyOf(buffer) : List.of());
        return java.util.Collections.unmodifiableMap(stateData);
    }

    /**
     * 补全 PendingApproval 中由 ToolApprovalInterceptor 留空的 agentName 和 nodeName。
     * <p>
     * InterruptPayload 和 PendingApproval 都是不可变 record，需要重建。
     */
    private PendingApproval fillApprovalContext(PendingApproval approval, String agentName, String nodeName) {
        com.ksyun.agent.core.approval.InterruptPayload original = approval.payload();
        // 只有真正为空时才补全
        if (original.agentName().isBlank() || original.nodeName().isBlank()) {
            com.ksyun.agent.core.approval.InterruptPayload filled = new com.ksyun.agent.core.approval.InterruptPayload(
                    original.approvalId(),
                    original.runId(),
                    original.threadId(),
                    original.userId(),
                    original.agentName().isBlank() ? agentName : original.agentName(),
                    original.nodeName().isBlank() ? nodeName : original.nodeName(),
                    original.reason(),
                    original.operationType(),
                    original.operationName(),
                    original.safeArguments(),
                    original.riskLevel(),
                    original.requestedAt(),
                    original.toolCallId(),
                    original.operationFingerprint()
            );
            return new PendingApproval(
                    filled,
                    approval.status(),
                    approval.decision(),
                    approval.createdAt(),
                    approval.updatedAt()
            );
        }
        return approval;
    }

    /**
     * 加载 Checkpoint。
     */
    public Optional<AgentCheckpoint> loadCheckpoint(String runId) {
        return checkpointStore.load(runId);
    }
}
