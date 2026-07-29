package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.run.RunStatus;

import java.util.Objects;

/**
 * React 线程持久化策略，纯 Java 实现。
 * <p>
 * 集中定义判断逻辑，不能在多个服务中用不同规则判断。
 * 判断必须可测试且确定。
 * <p>
 * 允许持久化：
 * 1. 正常 COMPLETE
 * 2. MaxIterations 节点产生了完整最终 Assistant 结果，且：
 *    - 没有 PendingApproval
 *    - 没有 PendingToolCall
 *    - 工具历史配对完整
 * <p>
 * 禁止持久化：
 * 1. SUSPENDED
 * 2. FAILURE
 * 3. 模型调用异常
 * 4. 工具执行中断
 * 5. 存在 PendingApproval
 * 6. 存在未完成 ToolCall
 * 7. 消息历史非法
 * 8. 状态映射失败
 * <p>
 * 不能只根据 HTTP 200 判断是否可保存。
 * 不能只根据 AgentResult.content 非空判断。
 */
public class ReactThreadPersistencePolicy {

    /**
     * 判断是否可持久化为 THREAD_MEMORY。
     * <p>
     * 失败轮不得覆盖旧稳定 Checkpoint。
     * 挂起轮不得保存 THREAD_MEMORY。
     * 不能只根据 HTTP 200 判断。
     * 不能只根据 AgentResult.content 非空判断。
     *
     * @param result     Agent 执行结果，不能为空
     * @param finalState 最终 ReactAgentState，不能为空
     * @return true 表示可持久化，false 表示不可持久化
     */
    public boolean isPersistable(
            AgentResult result,
            ReactAgentState finalState
    ) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");

        // SUSPENDED 不可持久化
        if (result.status() == RunStatus.SUSPENDED) {
            return false;
        }

        // FAILED 不可持久化
        if (result.status() == RunStatus.FAILED) {
            return false;
        }

        // 成功标记为 false 的结果不可持久化
        if (!result.success()) {
            return false;
        }

        // 存在 PendingApproval 不可持久化
        if (ReactStateKeys.getPendingApproval(finalState) != null) {
            return false;
        }

        // 存在未完成 ToolCall 不可持久化
        var pendingToolCalls = ReactStateKeys.getPendingToolCalls(finalState);
        if (pendingToolCalls != null && !pendingToolCalls.isEmpty()) {
            return false;
        }

        // executionBuffer 不为空不可持久化
        var executionBuffer = ReactStateKeys.getToolExecutionBuffer(finalState);
        if (executionBuffer != null && !executionBuffer.isEmpty()) {
            return false;
        }

        // executionCursor 不为 0 不可持久化
        if (ReactStateKeys.getToolExecutionCursor(finalState) != 0) {
            return false;
        }

        // COMPLETED 和 MAX_ITERATIONS（有最终内容且成功）可持久化
        // 此时 result.success=true, result.status=COMPLETED
        return result.status() == RunStatus.COMPLETED;
    }
}
