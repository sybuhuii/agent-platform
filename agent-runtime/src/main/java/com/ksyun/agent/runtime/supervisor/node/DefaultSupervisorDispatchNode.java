package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.supervisor.SupervisorChildRunLink;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.core.supervisor.SupervisorChildExecutionStatus;
import com.ksyun.agent.runtime.supervisor.SupervisorNodeNames;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import com.ksyun.agent.runtime.supervisor.checkpoint.SupervisorCheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor Dispatch 节点实现。
 * <p>
 * 按 pendingTasks 顺序逐个通过 ReactAgentEngine 执行子 Agent。
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * 第一步新增：
 * - 在调用子 Agent 之前创建 SupervisorChildRunLink 父子运行关联
 * - 将关联写入增强后的 AgentTask.context
 * - 子 Agent 发生 HITL 时，关联随 React State 保存进子 Checkpoint
 * - 保留子 Agent 原始 RunStatus（特别是 SUSPENDED 不再被重建成 FAILED）
 * - metadata 补充非敏感父子关联字段
 * <p>
 * 第二步新增：
 * - 初始化任务执行状态表 DISPATCH_TASKS
 * - 子 Agent 执行前状态为 RUNNING
 * - 子 Agent 返回后根据 RunStatus 正确转换状态
 * - 遇到 SUSPENDED 立即停止后续分派
 * - 未执行任务保持 NOT_STARTED
 * - 返回 DISPATCH_TASKS、LATEST_AGENT_RESULTS、SUSPENDED_CHILDREN 状态增量
 * <p>
 * 第三步新增：
 * - 遇到 SUSPENDED 后，在返回状态增量之前保存父 HITL_RECOVERY Checkpoint
 * - 保存成功后将 CHECKPOINT_ID 写入状态增量
 * - 保存失败时进入结构化失败路径，不伪装正常暂停
 * - 不在 Suspend 节点中保存 Checkpoint
 */
public class DefaultSupervisorDispatchNode implements SupervisorDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorDispatchNode.class);

    private final AgentRegistry agentRegistry;
    private final ReactAgentEngine reactAgentEngine;
    private final RunIdGenerator runIdGenerator;
    private final SupervisorCheckpointService checkpointService;

    public DefaultSupervisorDispatchNode(AgentRegistry agentRegistry,
                                          ReactAgentEngine reactAgentEngine,
                                          RunIdGenerator runIdGenerator,
                                          SupervisorCheckpointService checkpointService) {
        this.agentRegistry = agentRegistry;
        this.reactAgentEngine = reactAgentEngine;
        this.runIdGenerator = runIdGenerator;
        this.checkpointService = checkpointService;
    }

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        SupervisorDefinition definition = getSupervisorDefinition(state);
        RunContext parentContext = getRunContext(state);
        AgentTask rootTask = getRootTask(state);
        int supervisorIteration = getIteration(state);
        List<AgentTask> pendingTasks = getPendingTasks(state);

        // 检查是否已有 dispatchTasks（恢复模式）
        List<SupervisorChildExecution> existingDispatchTasks = getDispatchTasks(state);

        if (!existingDispatchTasks.isEmpty()) {
            // Checkpoint 会同时保留原始 pendingTasks 和执行状态表。
            // 只要已有 dispatchTasks，就必须以其状态为准继续恢复，避免把原任务重新初始化并重复执行。
            return dispatchResumedTasks(state, parentContext, rootTask, supervisorIteration, existingDispatchTasks);
        }

        if (pendingTasks.isEmpty()) {
            return Map.of(
                    FAILURE_ERROR_CODE, AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "No pending tasks to dispatch"
            );
        }

        // 生成分派批次 ID：{parentRunId}:dispatch:{supervisorIteration}
        String dispatchBatchId = parentContext.runId() + ":dispatch:" + supervisorIteration;

        // 8.1 初始化任务执行状态表：每个任务为 NOT_STARTED
        List<SupervisorChildExecution> dispatchTasks = new ArrayList<>(pendingTasks.size());
        for (int i = 0; i < pendingTasks.size(); i++) {
            dispatchTasks.add(SupervisorChildExecution.notStarted(pendingTasks.get(i), i));
        }

        List<AgentResult> results = new ArrayList<>();
        List<SupervisorChildExecution> suspendedChildren = new ArrayList<>();

        for (int dispatchIndex = 0; dispatchIndex < pendingTasks.size(); dispatchIndex++) {
            AgentTask task = pendingTasks.get(dispatchIndex);

            // 确认 agentName 属于 memberAgents
            if (!definition.memberAgents().contains(task.agentName())) {
                log.error("SupervisorDispatch: task agentName not in memberAgents: agentName={}", task.agentName());
                AgentResult failureResult = AgentResult.failure(task.agentName(),
                        AgentErrorCode.AGENT_NOT_FOUND.name(),
                        "Agent not in memberAgents: " + task.agentName());
                results.add(failureResult);
                // 无 runLink 的失败，直接标记 FAILED
                dispatchTasks.set(dispatchIndex, SupervisorChildExecution.failed(
                        task, dispatchIndex, null, failureResult));
                continue;
            }

            AgentDefinition agentDef = agentRegistry.getRequired(task.agentName());

            // 创建独立子 RunContext
            String childRunId = runIdGenerator.nextRunId();
            String childThreadId = parentContext.threadId() + "-" + task.taskId();
            RunContext childContext = new RunContext(
                    parentContext.userId(),
                    parentContext.sessionId(),
                    childThreadId,
                    childRunId,
                    parentContext.roles(),
                    parentContext.permissions()
            );

            // 创建父子运行关联
            SupervisorChildRunLink link = new SupervisorChildRunLink(
                    parentContext.runId(),
                    parentContext.threadId(),
                    rootTask.taskId(),
                    dispatchBatchId,
                    childRunId,
                    childThreadId,
                    task.taskId(),
                    dispatchIndex
            );

            // 8.2 子 Agent 执行前：NOT_STARTED → RUNNING
            dispatchTasks.set(dispatchIndex, SupervisorChildExecution.running(task, dispatchIndex, link));

            // 创建增强后的子 AgentTask：保留原始 context + 写入关联
            AgentTask childTask = createEnhancedTask(task, link);

            AgentResult result;
            try {
                result = reactAgentEngine.execute(agentDef, childTask, childContext);
            } catch (AgentFrameworkException e) {
                log.error("SupervisorDispatch: sub-agent execution failed: agentName={}, childRunId={}, errorCode={}",
                        task.agentName(), childRunId, e.getErrorCode());
                // 单个子Agent异常不破坏整个父图，转为失败AgentResult
                result = AgentResult.failure(task.agentName(),
                        e.getErrorCode().name(),
                        "Sub-agent execution failed");
            } catch (Exception e) {
                log.error("SupervisorDispatch: sub-agent unexpected error: agentName={}, childRunId={}",
                        task.agentName(), childRunId, e);
                result = AgentResult.failure(task.agentName(),
                        AgentErrorCode.INTERNAL_ERROR.name(),
                        "Sub-agent execution error");
            }

            // 8.2 子 Agent 返回后，根据 RunStatus 转换状态
            SupervisorChildExecution execution = resolveExecutionStatus(
                    task, dispatchIndex, link, result);
            dispatchTasks.set(dispatchIndex, execution);

            // 补充非敏感 metadata（保留原始 metadata + approvalRunId）
            // link 的其他字段已存在于 dispatchTasks 的 SupervisorChildExecution.runLink 中，
            // 不在 metadata 中重复存储
            Map<String, Object> meta = new HashMap<>();
            if (result.metadata() != null) {
                meta.putAll(result.metadata());
            }
            meta.put("approvalRunId", link.childRunId());

            // 保留子 Agent 原始 RunStatus，7参数构造器显式传递 status
            AgentResult enrichedResult = new AgentResult(
                    result.agentName(),
                    result.success(),
                    result.content(),
                    result.evidence(),
                    Collections.unmodifiableMap(meta),
                    result.errorCode(),
                    result.status()
            );

            results.add(enrichedResult);

            // 8.3 遇到 SUSPENDED 立即停止后续分派
            if (execution.status() == SupervisorChildExecutionStatus.SUSPENDED) {
                suspendedChildren.add(execution);
                log.info("SupervisorDispatch: child suspended, stopping dispatch: " +
                        "childRunId={}, approvalId={}, dispatchIndex={}",
                        childRunId, execution.approvalId(), dispatchIndex);
                break;
            }
        }

        // 8.4 Dispatch 节点返回状态增量
        Map<String, Object> updates = new HashMap<>();
        updates.put(DISPATCH_TASKS, List.copyOf(dispatchTasks));
        updates.put(LATEST_AGENT_RESULTS, List.copyOf(results));
        updates.put(SUSPENDED_CHILDREN, List.copyOf(suspendedChildren));

        // 第三步：遇到 SUSPENDED 后保存父 Checkpoint
        if (!suspendedChildren.isEmpty()) {
            AgentCheckpoint parentCheckpoint = saveParentCheckpoint(
                    state, dispatchTasks, suspendedChildren);
            // 保存成功后将 CHECKPOINT_ID 写入状态增量
            updates.put(CHECKPOINT_ID, parentCheckpoint.checkpointId());
        }

        return updates;
    }

    /**
     * 保存父 Supervisor HITL_RECOVERY Checkpoint。
     * <p>
     * 在 Dispatch 返回状态增量之前调用。
     * 保存失败时进入结构化失败路径：
     * - 不得继续返回一个看似正常的 Supervisor SUSPENDED
     * - 设置明确错误码和安全错误信息
     * - 不进入 Aggregate
     * - 不再次执行子 Agent
     * - 不删除已经存在的子 Agent Checkpoint
     * - 不伪造父 checkpointId
     */
    private AgentCheckpoint saveParentCheckpoint(
            com.ksyun.agent.runtime.supervisor.SupervisorAgentState state,
            List<SupervisorChildExecution> dispatchTasks,
            List<SupervisorChildExecution> suspendedChildren) {
        try {
            AgentCheckpoint checkpoint = checkpointService.suspend(
                    state, dispatchTasks, suspendedChildren,
                    SupervisorNodeNames.DISPATCH_AGENTS);
            log.info("SupervisorDispatch: parent checkpoint saved: checkpointId={}, runId={}",
                    checkpoint.checkpointId(), checkpoint.runId());
            return checkpoint;
        } catch (AgentFrameworkException e) {
            log.error("SupervisorDispatch: parent checkpoint save failed: runId={}, errorCode={}",
                    getRunContext(state).runId(), e.getErrorCode());
            // 保存失败：进入结构化失败路径
            // 不伪装正常暂停，抛出异常让图进入 FAILURE
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Failed to save Supervisor HITL checkpoint",
                    e);
        }
    }

    /**
     * 根据 AgentResult.status() 转换为 SupervisorChildExecutionStatus。
     * <p>
     * 必须检查仓库真实 RunStatus 枚举，对其他状态采用保守处理规则。
     */
    private SupervisorChildExecution resolveExecutionStatus(
            AgentTask task, int dispatchIndex,
            SupervisorChildRunLink link, AgentResult result) {

        RunStatus runStatus = result.status();
        if (runStatus == null) {
            // status 为空时按 success 推断，保守处理
            runStatus = result.success() ? RunStatus.COMPLETED : RunStatus.FAILED;
        }

        return switch (runStatus) {
            case COMPLETED -> SupervisorChildExecution.completed(task, dispatchIndex, link, result);
            case FAILED -> SupervisorChildExecution.failed(task, dispatchIndex, link, result);
            case SUSPENDED -> {
                // 8.3 从结果 metadata 中读取 approvalId，校验非空
                String approvalId = extractApprovalId(result);
                yield SupervisorChildExecution.suspended(task, dispatchIndex, link, result, approvalId);
            }
            // CREATED, RUNNING, INTERRUPTED — 保守处理为失败
            default -> {
                log.warn("SupervisorDispatch: unexpected RunStatus from child agent: status={}, " +
                        "childRunId={}, treating as FAILED", runStatus, link.childRunId());
                yield SupervisorChildExecution.failed(task, dispatchIndex, link, result);
            }
        };
    }

    /**
     * 从子 Agent 结果 metadata 中提取 approvalId。
     * <p>
     * 如果 SUSPENDED 结果缺少 approvalId，这是内部状态不完整，
     * 使用结构化错误语义进入失败路径，不伪造审批 ID。
     */
    private String extractApprovalId(AgentResult result) {
        Object approvalIdObj = result.metadata().get("approvalId");
        if (approvalIdObj == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "SUSPENDED result missing approvalId in metadata");
        }
        String approvalId = approvalIdObj.toString();
        if (approvalId.isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "SUSPENDED result has blank approvalId in metadata");
        }
        return approvalId;
    }

    /**
     * 恢复模式：从已有的 dispatchTasks 中只执行 NOT_STARTED 任务。
     * <p>
     * 恢复语义：
     * - 不重新执行 COMPLETED/FAILED 任务
     * - 只执行 NOT_STARTED 的任务
     * - RUNNING 状态不应出现（恢复时没有正在执行的任务）
     * - SUSPENDED 任务已经完成子审批恢复，不在此重新执行
     * - 遇到新的 SUSPENDED 仍立即停止后续分派
     * - 保存父 Checkpoint
     */
    private Map<String, Object> dispatchResumedTasks(
            com.ksyun.agent.runtime.supervisor.SupervisorAgentState state,
            RunContext parentContext,
            AgentTask rootTask,
            int supervisorIteration,
            List<SupervisorChildExecution> existingDispatchTasks) {

        SupervisorDefinition definition = getSupervisorDefinition(state);

        // 复制已有 dispatchTasks（保留 COMPLETED/FAILED/SUSPENDED 状态）
        List<SupervisorChildExecution> dispatchTasks = new ArrayList<>(existingDispatchTasks);

        List<AgentResult> results = new ArrayList<>();
        List<SupervisorChildExecution> suspendedChildren = new ArrayList<>();

        for (int i = 0; i < dispatchTasks.size(); i++) {
            SupervisorChildExecution exec = dispatchTasks.get(i);

            // 跳过非 NOT_STARTED 任务
            if (exec.status() != SupervisorChildExecutionStatus.NOT_STARTED) {
                // 已完成的任务可能有结果，收集到 results
                if (exec.result() != null) {
                    results.add(exec.result());
                }
                // 已暂停的任务也收集到 suspendedChildren
                if (exec.status() == SupervisorChildExecutionStatus.SUSPENDED) {
                    suspendedChildren.add(exec);
                }
                continue;
            }

            // 执行 NOT_STARTED 任务（与正常 dispatch 逻辑相同）
            AgentTask task = exec.task();
            int dispatchIndex = exec.dispatchIndex();

            if (!definition.memberAgents().contains(task.agentName())) {
                AgentResult failureResult = AgentResult.failure(task.agentName(),
                        AgentErrorCode.AGENT_NOT_FOUND.name(),
                        "Agent not in memberAgents: " + task.agentName());
                results.add(failureResult);
                dispatchTasks.set(i, SupervisorChildExecution.failed(task, dispatchIndex, null, failureResult));
                continue;
            }

            AgentDefinition agentDef = agentRegistry.getRequired(task.agentName());
            String childRunId = runIdGenerator.nextRunId();
            String childThreadId = parentContext.threadId() + "-" + task.taskId();
            RunContext childContext = new RunContext(
                    parentContext.userId(), parentContext.sessionId(),
                    childThreadId, childRunId,
                    parentContext.roles(), parentContext.permissions());

            SupervisorChildRunLink link = new SupervisorChildRunLink(
                    parentContext.runId(), parentContext.threadId(), rootTask.taskId(),
                    parentContext.runId() + ":dispatch:" + supervisorIteration + ":resume",
                    childRunId, childThreadId, task.taskId(), dispatchIndex);

            dispatchTasks.set(i, SupervisorChildExecution.running(task, dispatchIndex, link));
            AgentTask childTask = createEnhancedTask(task, link);

            AgentResult result;
            try {
                result = reactAgentEngine.execute(agentDef, childTask, childContext);
            } catch (AgentFrameworkException e) {
                result = AgentResult.failure(task.agentName(), e.getErrorCode().name(), "Sub-agent execution failed");
            } catch (Exception e) {
                result = AgentResult.failure(task.agentName(), AgentErrorCode.INTERNAL_ERROR.name(), "Sub-agent execution error");
            }

            SupervisorChildExecution execution = resolveExecutionStatus(task, dispatchIndex, link, result);
            dispatchTasks.set(i, execution);

            // 补充 metadata
            Map<String, Object> meta = new HashMap<>();
            if (result.metadata() != null) {
                meta.putAll(result.metadata());
            }
            meta.put("parentRunId", link.parentRunId());
            meta.put("parentThreadId", link.parentThreadId());
            meta.put("parentTaskId", link.parentTaskId());
            meta.put("dispatchBatchId", link.dispatchBatchId());
            meta.put("approvalRunId", link.childRunId());
            meta.put("childThreadId", link.childThreadId());
            meta.put("childTaskId", link.childTaskId());
            meta.put("dispatchIndex", link.dispatchIndex());

            AgentResult enrichedResult = new AgentResult(
                    result.agentName(), result.success(), result.content(), result.evidence(),
                    Collections.unmodifiableMap(meta), result.errorCode(), result.status());
            results.add(enrichedResult);

            if (execution.status() == SupervisorChildExecutionStatus.SUSPENDED) {
                suspendedChildren.add(execution);
                log.info("SupervisorDispatch(resume): child suspended, stopping dispatch: childRunId={}", childRunId);
                break;
            }
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(DISPATCH_TASKS, List.copyOf(dispatchTasks));
        updates.put(LATEST_AGENT_RESULTS, List.copyOf(results));
        updates.put(SUSPENDED_CHILDREN, List.copyOf(suspendedChildren));

        // 遇到 SUSPENDED 后保存父 Checkpoint
        if (!suspendedChildren.isEmpty()) {
            AgentCheckpoint parentCheckpoint = saveParentCheckpoint(state, dispatchTasks, suspendedChildren);
            updates.put(CHECKPOINT_ID, parentCheckpoint.checkpointId());
        }

        return updates;
    }

    /**
     * 创建增强后的子 AgentTask。
     * <p>
     * 保留原始 context 中的业务字段，新增 SupervisorChildRunLink。
     * 对保留键冲突采用安全覆盖策略（框架内部键，不信任调用方值）。
     * 新 context 对外不可变。
     *
     * @param original 原始 AgentTask，不修改
     * @param link     父子运行关联
     * @return 增强后的 AgentTask
     */
    private AgentTask createEnhancedTask(AgentTask original, SupervisorChildRunLink link) {
        Map<String, Object> enhancedContext = new HashMap<>(original.context().size() + 1);
        enhancedContext.putAll(original.context());

        // 保留键冲突：安全覆盖，不信任调用方值
        if (original.context().containsKey(SupervisorChildRunLink.TASK_CONTEXT_KEY)) {
            log.warn("SupervisorDispatch: overriding existing context key '{}' in task={}, " +
                    "framework reserves this key for internal use",
                    SupervisorChildRunLink.TASK_CONTEXT_KEY, original.taskId());
        }
        enhancedContext.put(SupervisorChildRunLink.TASK_CONTEXT_KEY, link);

        return new AgentTask(
                original.taskId(),
                original.agentName(),
                original.instruction(),
                Collections.unmodifiableMap(enhancedContext)
        );
    }
}
