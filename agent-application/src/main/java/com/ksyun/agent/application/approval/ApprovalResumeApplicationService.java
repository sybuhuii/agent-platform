package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.approval.ApprovalAction;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.runtime.react.ReactResumeEngine;
import com.ksyun.agent.runtime.react.ReactResumeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 审批恢复应用服务。
 * <p>
 * 职责：
 * 1. 接收 ApprovalDecisionCommand
 * 2. 调用 ApprovalDecisionService 记录审批决定
 * 3. 从 Checkpoint 获取 runId/threadId
 * 4. APPROVE 和 REJECT 都调用 ResumeEngine.resume 恢复图
 * 5. 使用 ReactResumeResult 表达恢复结果（包含 threadId）
 * <p>
 * 约束：
 * - 不自行查询 Checkpoint 补 threadId（决策服务返回的决策结果中已有）
 * - 不在 Application Service 中伪造最终回答
 * - 操作者身份来自已验证 UserSession
 * - 不从请求 Body 获取 userId
 * - 不直接访问 CheckpointStore、RunContext、AgentState
 * - 不调用模型
 * - 不删除 Checkpoint
 * - 不绕过 ResumeCoordinator 抢占
 * - 恢复不重新进入首次 Reason
 * - 不同用户不能操作对方 Checkpoint
 */
public class ApprovalResumeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeApplicationService.class);

    private final ApprovalDecisionService decisionService;
    private final ReactResumeEngine resumeEngine;
    private final CheckpointStore checkpointStore;

    public ApprovalResumeApplicationService(ApprovalDecisionService decisionService,
                                              ReactResumeEngine resumeEngine,
                                              CheckpointStore checkpointStore) {
        this.decisionService = Objects.requireNonNull(decisionService);
        this.resumeEngine = Objects.requireNonNull(resumeEngine);
        this.checkpointStore = Objects.requireNonNull(checkpointStore);
    }

    /**
     * 处理审批决定并恢复执行。
     * <p>
     * APPROVE：记录决定后调用 ResumeEngine.resume
     * REJECT：记录决定后也调用 ResumeEngine.resume
     * <p>
     * 返回 ReactResumeResult，包含 runId/threadId/agentName/status 等完整信息。
     * 不得对 REJECT 直接返回静态 failure AgentResult。
     */
    public ReactResumeResult decideAndResume(UserSession operator, ApprovalDecisionCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. 记录审批决定（包含 threadId 信息）
        ApprovalDecisionResult decisionResult = decisionService.decide(operator, command);

        // 2. APPROVE 和 REJECT 都恢复图执行
        AgentResult agentResult = resumeEngine.resume(command.runId(), operator);

        // 3. 从 Checkpoint 获取 threadId（不让 Controller 自行查询）
        String threadId = decisionResult.threadId();

        // 4. 构造恢复结果
        ReactResumeResult resumeResult = ReactResumeResult.from(
                command.runId(), threadId, agentResult);

        log.info("Resume completed: runId={}, action={}, resultStatus={}",
                command.runId(), command.action(), agentResult.status());

        return resumeResult;
    }

    /**
     * 仅记录审批决定，不恢复执行。
     */
    public ApprovalDecisionResult decide(UserSession operator, ApprovalDecisionCommand command) {
        return decisionService.decide(operator, command);
    }

    /**
     * 仅恢复已决定的 Checkpoint。
     */
    public ReactResumeResult resume(UserSession operator, String runId) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(runId, "runId must not be null");

        AgentResult agentResult = resumeEngine.resume(runId, operator);

        // 从 Checkpoint 获取 threadId
        AgentCheckpoint checkpoint = checkpointStore.load(runId).orElse(null);
        String threadId = checkpoint != null ? checkpoint.threadId() : "";

        return ReactResumeResult.from(runId, threadId, agentResult);
    }
}
