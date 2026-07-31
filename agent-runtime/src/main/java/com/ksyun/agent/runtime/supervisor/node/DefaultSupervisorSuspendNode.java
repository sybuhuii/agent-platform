package com.ksyun.agent.runtime.supervisor.node;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.core.supervisor.SupervisorChildExecution;
import com.ksyun.agent.runtime.supervisor.SupervisorStopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.ksyun.agent.runtime.supervisor.SupervisorStateKeys.*;

/**
 * 默认 Supervisor Suspend 节点实现。
 * <p>
 * 当子 Agent 返回 SUSPENDED 时，Supervisor 进入暂停终态。
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * 职责仅限：
 * 1. 校验存在暂停子任务
 * 2. 构造 Supervisor 自己的 AgentResult（status=SUSPENDED）
 * 3. 设置 Supervisor RunStatus.SUSPENDED
 * 4. 设置 SupervisorStopReason.SUSPENDED
 * 5. 返回状态增量
 * 6. 进入 END
 * <p>
 * 不得：
 * - 调用模型、工具、子 Agent
 * - 保存、更新或删除 Checkpoint
 * - 调用 Application Service
 * - 访问 Spring 容器
 * - 进入 Aggregate
 * - 清空任务执行表
 * - 清空暂停子任务
 * - 把暂停转换为失败
 */
public class DefaultSupervisorSuspendNode implements SupervisorSuspendNode {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorSuspendNode.class);

    @Override
    public Map<String, Object> apply(com.ksyun.agent.runtime.supervisor.SupervisorAgentState state) throws Exception {
        SupervisorDefinition definition = getSupervisorDefinition(state);
        List<SupervisorChildExecution> suspendedChildren = getSuspendedChildren(state);

        // 1. 校验存在暂停子任务
        if (suspendedChildren == null || suspendedChildren.isEmpty()) {
            log.error("SupervisorSuspend: no suspended children found, this is an invalid state");
            return Map.of(
                    FAILURE_ERROR_CODE, com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    STOP_REASON, SupervisorStopReason.INVALID_STATE,
                    FAILURE_MESSAGE, "Suspend node invoked without suspended children"
            );
        }

        // 取第一个暂停子任务构建顶层 metadata（API 兼容）
        SupervisorChildExecution primarySuspended = suspendedChildren.get(0);
        AgentResult childResult = primarySuspended.result();

        // 2. 构造 Supervisor 自己的 AgentResult
        Map<String, Object> metadata = buildMetadata(primarySuspended, childResult);

        AgentResult result = new AgentResult(
                definition.name(),
                false,
                "运行已暂停，等待人工审批。",
                List.of(),
                Collections.unmodifiableMap(metadata),
                "APPROVAL_REQUIRED",
                RunStatus.SUSPENDED
        );

        log.info("SupervisorSuspend: supervisor suspended, approvalId={}, childRunId={}, parentRunId={}",
                primarySuspended.approvalId(),
                primarySuspended.runLink().childRunId(),
                primarySuspended.runLink().parentRunId());

        // 3-5. 设置状态并返回
        return Map.of(
                FINAL_RESULT, result,
                STOP_REASON, SupervisorStopReason.SUSPENDED,
                RUN_STATUS, RunStatus.SUSPENDED
        );
    }

    /**
     * 构造 Supervisor 暂停结果的 metadata。
     * <p>
     * 包含第一个暂停子任务的非敏感关联信息。
     * 不暴露原始工具参数、完整 InterruptPayload、RunContext、Session ID 或权限集合。
     */
    private Map<String, Object> buildMetadata(SupervisorChildExecution suspended, AgentResult childResult) {
        Map<String, Object> meta = new HashMap<>();

        // 子 Agent 审批 ID
        meta.put("approvalId", suspended.approvalId());

        // 子 Agent childRunId — 前端审批时使用此 runId
        meta.put("approvalRunId", suspended.runLink().childRunId());

        // 父 Supervisor runId（Controller 用此字段判断 isNested）
        meta.put("parentRunId", suspended.runLink().parentRunId());

        // link 的其他字段（parentThreadId, childThreadId, childTaskId, dispatchBatchId,
        // dispatchIndex）已存在于 dispatchTasks 的 SupervisorChildExecution.runLink 中，
        // 不在 metadata 中重复存储

        // 从子 Agent 已有的安全 metadata 获取操作信息
        if (childResult.metadata() != null) {
            copyIfPresent(childResult.metadata(), meta, "operationName");
            copyIfPresent(childResult.metadata(), meta, "riskLevel");
            copyIfPresent(childResult.metadata(), meta, "requestedAt");
        }

        return meta;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}
