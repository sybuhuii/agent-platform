package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.approval.ApprovalAction;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.runtime.react.ReactResumeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 审批恢复应用服务。
 * <p>
 * 职责：
 * 1. 接收 ApprovalDecisionCommand
 * 2. 调用 ApprovalDecisionService 记录审批决定
 * 3. APPROVE 和 REJECT 都调用 ResumeEngine.resume 恢复图
 * <p>
 * REJECT 恢复流程：
 * - ToolApprovalInterceptor 不调用 TerminalToolExecutor
 * - 返回 APPROVAL_REJECTED 失败 ToolResult
 * - 经 Observe 形成 error=true 的 ToolAgentMessage 回灌模型
 * - 模型说明操作被拒绝
 * - 不得在 Application Service 中伪造最终回答
 * <p>
 * 约束：
 * - 操作者身份来自已验证 UserSession
 * - 不得从请求 Body 获取 userId
 * - 不得通过 approvalId 单独加载并执行
 * - 不得访问 CheckpointStore、RunContext、AgentState
 * - 不得调用模型
 * - 不得删除 Checkpoint
 * - 不得绕过 ResumeCoordinator 抢占
 * - 恢复后工具执行通过 ToolInvocationGateway
 * - 恢复不重新进入首次 Reason
 * - 不同用户不能操作对方 Checkpoint
 */
public class ApprovalResumeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeApplicationService.class);

    private final ApprovalDecisionService decisionService;
    private final ReactResumeEngine resumeEngine;

    public ApprovalResumeApplicationService(ApprovalDecisionService decisionService,
                                              ReactResumeEngine resumeEngine) {
        this.decisionService = Objects.requireNonNull(decisionService);
        this.resumeEngine = Objects.requireNonNull(resumeEngine);
    }

    /**
     * 处理审批决定并恢复执行。
     * <p>
     * APPROVE：记录决定后调用 ResumeEngine.resume
     * REJECT：记录决定后也调用 ResumeEngine.resume（拒绝结果经 Observe 回灌模型）
     * <p>
     * 不得对 REJECT 直接返回静态 failure AgentResult。
     * REJECT 应恢复图让 Agent 继续执行：
     * Observe REJECTED ToolResult → Reason → 最终回答。
     */
    public AgentResult decideAndResume(UserSession operator, ApprovalDecisionCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. 记录审批决定
        ApprovalDecisionResult decisionResult = decisionService.decide(operator, command);

        // 2. APPROVE 和 REJECT 都恢复图执行
        try {
            AgentResult result = resumeEngine.resume(command.runId(), operator);

            log.info("Resume completed: runId={}, action={}, resultStatus={}",
                    command.runId(), command.action(), result.status());
            return result;
        } catch (AgentFrameworkException e) {
            log.error("Resume failed: runId={}, action={}, errorCode={}",
                    command.runId(), command.action(), e.getErrorCode());

            // 并发恢复冲突，直接抛出
            if (e.getErrorCode() == AgentErrorCode.RUN_ALREADY_RESUMING
                    || e.getErrorCode() == AgentErrorCode.CHECKPOINT_CONFLICT) {
                throw e;
            }
            // 其他恢复失败
            throw e;
        }
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
    public AgentResult resume(UserSession operator, String runId) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        return resumeEngine.resume(runId, operator);
    }
}
