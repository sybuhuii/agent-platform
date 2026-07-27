package com.ksyun.agent.runtime.react.checkpoint;

import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.InterruptReason;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ReAct Checkpoint 服务。
 * <p>
 * 负责 Checkpoint 的保存和加载，将 ReactAgentState 的关键数据
 * 序列化为 AgentCheckpoint。
 * <p>
 * 保存内容：
 * - runId, threadId, status, version, updatedAt
 * - messages, pendingToolCalls, latestToolResults, iteration
 * - approval（当状态为 INTERRUPTED 时）
 * <p>
 * 不保存的内容：
 * - agentDefinition（可从 AgentRegistry 恢复）
 * - task（可从上层传入）
 * - runContext 中的密码、credentialHash、sessionId
 * <p>
 * 不实现恢复逻辑，不修改 RunContext。
 * 纯 Java 实现，不依赖 Spring。
 */
public class ReactCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(ReactCheckpointService.class);

    private final CheckpointStore checkpointStore;

    public ReactCheckpointService(CheckpointStore checkpointStore) {
        this.checkpointStore = checkpointStore;
    }

    /**
     * 保存 Checkpoint。
     * <p>
     * 当 status 为 INTERRUPTED 时，构造 PendingApproval 并保存到 Checkpoint。
     *
     * @param runId      运行 ID
     * @param threadId   线程 ID
     * @param status     运行状态
     * @param stateData  状态数据快照
     * @param version    当前版本号
     * @param toolCall   触发中断的工具调用（可为 null）
     * @param interruptReason 中断原因（可为 null）
     * @param reason     中断描述（可为 null）
     * @param runContext 运行上下文
     * @return 保存后的 Checkpoint
     */
    public AgentCheckpoint saveCheckpoint(String runId,
                                            String threadId,
                                            RunStatus status,
                                            Map<String, Object> stateData,
                                            long version,
                                            ToolCall toolCall,
                                            InterruptReason interruptReason,
                                            String reason,
                                            RunContext runContext) {
        PendingApproval approval = null;

        if (status == RunStatus.INTERRUPTED) {
            if (toolCall == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "toolCall must not be null when status is INTERRUPTED");
            }
            if (interruptReason == null) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "interruptReason must not be null when status is INTERRUPTED");
            }
            if (reason == null || reason.isBlank()) {
                throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT,
                        "reason must not be blank when status is INTERRUPTED");
            }

            // 构建 safeArguments：只保留参数名列表，不包含值
            Map<String, Object> safeArgs = buildSafeArguments(toolCall);

            approval = new PendingApproval(
                    generateApprovalId(),
                    runId,
                    threadId,
                    toolCall,
                    interruptReason,
                    reason,
                    safeArgs,
                    ApprovalStatus.PENDING,
                    Instant.now()
            );
        }

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                runId,
                threadId,
                status,
                stateData != null ? new HashMap<>(stateData) : Map.of(),
                approval,
                version,
                Instant.now()
        );

        checkpointStore.save(checkpoint);

        log.info("Checkpoint saved: runId={}, status={}, version={}, hasApproval={}",
                runId, status, version, approval != null);

        return checkpoint;
    }

    /**
     * 加载 Checkpoint。
     *
     * @param runId 运行 ID
     * @return Checkpoint，不存在时返回 Optional.empty
     */
    public Optional<AgentCheckpoint> loadCheckpoint(String runId) {
        return checkpointStore.load(runId);
    }

    /**
     * 构建脱敏参数：只保留参数名列表，不包含值。
     */
    private Map<String, Object> buildSafeArguments(ToolCall toolCall) {
        if (toolCall.arguments() == null || toolCall.arguments().isEmpty()) {
            return Map.of();
        }
        // 只保留参数名列表
        Map<String, Object> safeArgs = new HashMap<>();
        safeArgs.put("argumentNames", toolCall.arguments().keySet());
        return safeArgs;
    }

    private String generateApprovalId() {
        return "apr-" + UUID.randomUUID();
    }
}
