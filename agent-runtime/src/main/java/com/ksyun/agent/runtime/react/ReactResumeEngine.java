package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.run.RunStatus;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.checkpoint.CheckpointResumeCoordinator;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointLifecycleService;
import com.ksyun.agent.runtime.react.checkpoint.ReactCheckpointStateMapper;
import com.ksyun.agent.runtime.react.checkpoint.ReactResumeValidator;
import com.ksyun.agent.core.security.UserSession;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static com.ksyun.agent.runtime.react.ReactStateKeys.*;

/**
 * ReAct 恢复引擎，纯 Java 实现。
 * <p>
 * 完整流程：
 * 1. 完整 Validator 校验在抢占前完成
 * 2. 原子抢占 SUSPENDED → RESUMING
 * 3. 从 Checkpoint 重建 ReactAgentState（全部纳入统一失败生命周期）
 * 4. 每次 resume 独立编译恢复图（CompiledGraph 线程安全不确定）
 * 5. 从 execute_tools 节点执行
 * 6. 批准时真实执行危险工具一次
 * 7. 拒绝时生成失败 ToolResult 并进入 Observe
 * 8. 后续 Reason 继续生成最终回答
 * 9. 保持原 runId 和 threadId
 * 10. 再次挂起时返回新的 approvalId
 * 11. 正常完成时由 LifecycleService 处理终态和条件清理
 * 12. 框架异常时由 LifecycleService 将已抢占 Checkpoint 标记为 FAILED
 * 13. 普通 ToolResult 失败经模型正常处理，不标记 Checkpoint FAILED
 * 14. 再次 SUSPENDED 不标记 FAILED 或删除
 * 15. finalResult 缺失必须标记 FAILED
 * <p>
 * 不得在共享图实例中保存请求 State。
 * 不得跨请求复用可变 ReactAgentState。
 * 不得用全局锁串行图执行。
 * 不得内嵌重复生命周期实现（使用 LifecycleService）。
 * 不得留下无法再次处理的 RESUMING Checkpoint。
 */
public class ReactResumeEngine {

    private static final Logger log = LoggerFactory.getLogger(ReactResumeEngine.class);

    private final CheckpointResumeCoordinator resumeCoordinator;
    private final ReactCheckpointStateMapper stateMapper;
    private final ReactResumeValidator resumeValidator;
    private final ReactCheckpointLifecycleService lifecycleService;
    private final ReactAgentGraphFactory graphFactory;

    public ReactResumeEngine(
            CheckpointResumeCoordinator resumeCoordinator,
            ReactCheckpointStateMapper stateMapper,
            ReactResumeValidator resumeValidator,
            ReactCheckpointLifecycleService lifecycleService,
            ReactAgentGraphFactory graphFactory) {
        this.resumeCoordinator = Objects.requireNonNull(resumeCoordinator);
        this.stateMapper = Objects.requireNonNull(stateMapper);
        this.resumeValidator = Objects.requireNonNull(resumeValidator);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.graphFactory = Objects.requireNonNull(graphFactory);
    }

    /**
     * 恢复执行。
     * <p>
     * APPROVE 和 REJECT 都通过此方法恢复图执行。
     * 完整 Validator 校验在抢占前完成。
     * 抢占成功后所有步骤纳入统一失败生命周期。
     *
     * @param runId    运行 ID
     * @param operator 当前操作用户
     * @return Agent 执行结果
     */
    public AgentResult resume(String runId, UserSession operator) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        // 1. 抢占前：先加载 Checkpoint 做完整 Validator 校验
        AgentCheckpoint preCheckCp = resumeCoordinator.loadForValidation(runId);
        resumeValidator.validateForResume(preCheckCp, operator, runId);

        // 2. 原子抢占 SUSPENDED → RESUMING
        AgentCheckpoint resumingCheckpoint = resumeCoordinator.acquireForResume(runId, operator);

        log.info("Resuming ReAct execution: runId={}, checkpointId={}, version={}, approvalId={}",
                runId, resumingCheckpoint.checkpointId(), resumingCheckpoint.version(),
                resumingCheckpoint.pendingApproval().approvalId());

        // 3. 抢占成功后，所有步骤纳入统一失败生命周期
        AgentResult finalResult;
        try {
            // 3a. 从 Checkpoint 重建 ReactAgentState
            ReactAgentState resumeState = stateMapper.fromCheckpointForResume(resumingCheckpoint);

            // 3b. 每次独立编译恢复图（CompiledGraph线程安全不确定，不复用共享实例）
            CompiledGraph<ReactAgentState> resumeGraph = graphFactory.compileForResume();

            // 3c. 调用恢复图
            ReactAgentState finalState = resumeGraph.invoke(resumeState.data())
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Resume graph execution returned empty state"));

            // 3d. 读取最终结果
            finalResult = getFinalResult(finalState);
            if (finalResult == null) {
                throw new AgentFrameworkException(
                        AgentErrorCode.INTERNAL_ERROR,
                        "Resume execution completed without finalResult");
            }
        } catch (AgentFrameworkException e) {
            log.error("Resume execution failed: runId={}, errorCode={}", runId, e.getErrorCode());
            // 将已抢占 Checkpoint 标记为 FAILED（LifecycleService 处理）
            try {
                lifecycleService.fail(resumingCheckpoint, e.getErrorCode());
            } catch (AgentFrameworkException lifecycleEx) {
                log.warn("Lifecycle fail also conflicted: runId={}, lifecycleErrorCode={}",
                        runId, lifecycleEx.getErrorCode());
            }
            throw e;
        } catch (Exception e) {
            log.error("Resume execution unexpected error: runId={}", runId, e);
            try {
                lifecycleService.fail(resumingCheckpoint, AgentErrorCode.INTERNAL_ERROR);
            } catch (AgentFrameworkException lifecycleEx) {
                log.warn("Lifecycle fail also conflicted: runId={}", runId, lifecycleEx.getErrorCode());
            }
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR, "Resume execution failed", e);
        }

        // 4. 处理 Checkpoint 生命周期
        handleLifecycle(runId, resumingCheckpoint, finalResult);

        return finalResult;
    }

    /**
     * 根据 AgentResult 状态处理 Checkpoint 生命周期。
     * <p>
     * - 再次 SUSPENDED：不标记 FAILED 或删除，由 ReactCheckpointService 更新为 SUSPENDED
     * - COMPLETED + success：LifecycleService complete
     * - COMPLETED + failure（普通工具失败经模型正常处理）：LifecycleService complete
     * - FAILED：LifecycleService fail
     * - finalResult null：已在上层抛异常
     * - LifecycleService 版本冲突不伪造失败结果，但框架异常已在上层抛出
     */
    private void handleLifecycle(String runId, AgentCheckpoint resumingCheckpoint, AgentResult finalResult) {
        if (finalResult.status() == RunStatus.SUSPENDED) {
            // 再次挂起：不标记 FAILED 或删除
            // ReactCheckpointService 已在 ToolExecutionNode 中保存了新 Checkpoint
            log.info("Resume resulted in re-suspension: runId={}", runId);
            return;
        }

        if (finalResult.status() == RunStatus.COMPLETED) {
            // 正常完成（包括 APPROVE 后成功、REJECT 后模型解释完成）
            try {
                lifecycleService.complete(resumingCheckpoint);
            } catch (AgentFrameworkException e) {
                // 版本冲突：可能已被再次挂起，不伪造失败结果
                log.warn("Lifecycle complete conflicted: runId={}, errorCode={}", runId, e.getErrorCode());
            }
            return;
        }

        if (finalResult.status() == RunStatus.FAILED) {
            // 框架级失败：LifecycleService fail
            try {
                lifecycleService.fail(resumingCheckpoint, AgentErrorCode.RESUME_FAILED);
            } catch (AgentFrameworkException e) {
                log.warn("Lifecycle fail also conflicted: runId={}", runId, e.getErrorCode());
            }
            return;
        }

        // 其他状态按正常完成处理
        try {
            lifecycleService.complete(resumingCheckpoint);
        } catch (AgentFrameworkException e) {
            log.warn("Lifecycle complete conflicted: runId={}, errorCode={}", runId, e.getErrorCode());
        }
    }

    private AgentResult getFinalResult(ReactAgentState state) {
        return state.<AgentResult>value(FINAL_RESULT).orElse(null);
    }
}
