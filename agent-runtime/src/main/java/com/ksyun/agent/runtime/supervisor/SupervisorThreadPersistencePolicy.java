package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.run.RunStatus;

import java.util.Objects;

/**
 * Supervisor 线程持久化策略，纯 Java 实现。
 * <p>
 * 集中定义判断逻辑，不能在多个服务中用不同规则判断。
 * 判断必须可测试且确定。
 * <p>
 * 允许持久化：
 * 1. 正常 COMPLETE
 * 2. MaxIterations 产生稳定最终结果，并且：
 *    - 没有未完成 dispatch
 *    - 没有正在运行的子 Agent
 *    - 消息历史完整
 *    - 没有失败状态
 * <p>
 * 禁止持久化：
 * 1. FAILURE
 * 2. 模型调用异常
 * 3. 子 Agent 尚未返回
 * 4. 仍存在 pendingDispatch
 * 5. 父状态不完整
 * 6. 状态映射失败
 * 7. 子 Agent 返回 SUSPENDED 而父流程未形成稳定终态
 * 8. 任何需要未来 Supervisor 恢复的中间状态
 * <p>
 * 失败轮不得覆盖旧稳定 THREAD_MEMORY。
 * 不能仅根据结果 content 非空判断。
 * 不能仅根据 HTTP 200 判断。
 */
public class SupervisorThreadPersistencePolicy {

    /**
     * 判断是否可持久化为 THREAD_MEMORY。
     * <p>
     * 失败轮不得覆盖旧稳定 Checkpoint。
     * SUSPENDED 不得保存 THREAD_MEMORY。
     * 不能只根据 HTTP 200 判断。
     * 不能只根据 AgentResult.content 非空判断。
     *
     * @param result     Supervisor 执行结果，不能为空
     * @param finalState 最终 SupervisorAgentState，不能为空
     * @return true 表示可持久化，false 表示不可持久化
     */
    public boolean isPersistable(
            AgentResult result,
            SupervisorAgentState finalState
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

        // 仍存在 DISPATCH 决策（pendingDispatch）不可持久化
        SupervisorDecision decision = SupervisorStateKeys.getDecision(finalState);
        if (decision != null && decision.action() == SupervisorAction.DISPATCH) {
            return false;
        }

        // 仍有待分派任务不可持久化
        var pendingTasks = SupervisorStateKeys.getPendingTasks(finalState);
        if (pendingTasks != null && !pendingTasks.isEmpty()) {
            return false;
        }

        // finalResult 为空不可持久化
        AgentResult finalResult = SupervisorStateKeys.getFinalResult(finalState);
        if (finalResult == null) {
            return false;
        }

        // COMPLETED 且成功可持久化
        return result.status() == RunStatus.COMPLETED;
    }
}
