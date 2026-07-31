package com.ksyun.agent.runtime.supervisor.checkpoint;

import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.CheckpointPurpose;
import com.ksyun.agent.core.supervisor.SupervisorChildRunLink;
import com.ksyun.agent.runtime.react.ReactStateKeys;

import java.util.Map;
import java.util.Objects;

/**
 * 集中解析器：从子 Agent Checkpoint 中读取 SupervisorChildRunLink。
 * <p>
 * 单一职责：只做解析，不修改 Checkpoint，不调用 Store，不调用模型。
 * <p>
 * 数据来源：子 Checkpoint stateData → TASK → AgentTask → context → Link。
 * 不只信任父 AgentResult.metadata。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 */
public class SupervisorChildRunLinkResolver {

    /**
     * 从子 Agent Checkpoint 中解析 SupervisorChildRunLink。
     *
     * @param childCheckpoint 子 Agent HITL_RECOVERY Checkpoint
     * @return 父子运行关联
     * @throws AgentFrameworkException 解析失败
     */
    public SupervisorChildRunLink resolve(AgentCheckpoint childCheckpoint) {
        Objects.requireNonNull(childCheckpoint, "childCheckpoint must not be null");

        // 校验 executionType == REACT_AGENT
        if (childCheckpoint.executionType() != CheckpointExecutionType.REACT_AGENT) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Expected REACT_AGENT checkpoint, got " + childCheckpoint.executionType());
        }

        // 校验 purpose == HITL_RECOVERY
        if (childCheckpoint.purpose() != CheckpointPurpose.HITL_RECOVERY) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Expected HITL_RECOVERY checkpoint, got " + childCheckpoint.purpose());
        }

        // 从 stateData 中读取 TASK
        Map<String, Object> stateData = childCheckpoint.stateData();
        if (stateData == null || stateData.isEmpty()) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint stateData is empty");
        }

        Object taskObj = stateData.get(ReactStateKeys.TASK);
        if (!(taskObj instanceof AgentTask task)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint stateData missing or invalid TASK");
        }

        // 从 AgentTask.context 中读取 Link
        Object linkObj = task.context().get(SupervisorChildRunLink.TASK_CONTEXT_KEY);
        if (!(linkObj instanceof SupervisorChildRunLink link)) {
            throw new AgentFrameworkException(AgentErrorCode.CHECKPOINT_NOT_RESUMABLE,
                    "Child checkpoint AgentTask.context missing SupervisorChildRunLink");
        }

        return link;
    }
}
