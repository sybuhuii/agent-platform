package com.ksyun.agent.application.approval;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.approval.ApprovalAction;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.approval.PendingApproval;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunStatus;
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
 * 3. 根据 Approve/Reject 执行恢复或返回拒绝结果
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
     * REJECT：记录决定后返回拒绝 AgentResult
     *
     * @param operator 当前操作用户
     * @param command  审批决定命令
     * @return Agent 执行结果
     */
    public AgentResult decideAndResume(UserSession operator, ApprovalDecisionCommand command) {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(command, "command must not be null");

        // 1. 记录审批决定
        ApprovalDecisionResult decisionResult = decisionService.decide(operator, command);

        // 2. 根据审批动作决定后续操作
        if (command.action() == ApprovalAction.REJECT) {
            // REJECT：返回拒绝结果，不需要恢复执行
            return AgentResult.failure(
                    "unknown",
                    AgentErrorCode.APPROVAL_REJECTED.name(),
                    "人工审批已拒绝该工具操作，运行已终止。"
            );
        }

        // 3. APPROVE：恢复执行
        try {
            AgentResult result = resumeEngine.resume(command.runId(), operator);

            // 恢复结果可能包含 SUSPENDED（再次挂起），不得转换为 FAILED
            log.info("Resume completed: runId={}, resultStatus={}", command.runId(), result.status());
            return result;
        } catch (AgentFrameworkException e) {
            log.error("Resume failed: runId={}, errorCode={}", command.runId(), e.getErrorCode());

            // 恢复失败时返回错误结果
            if (e.getErrorCode() == AgentErrorCode.RUN_ALREADY_RESUMING
                    || e.getErrorCode() == AgentErrorCode.CHECKPOINT_CONFLICT) {
                // 并发恢复冲突，返回明确错误
                throw e;
            }
            // 其他恢复失败
            return AgentResult.failure(
                    "unknown",
                    e.getErrorCode().name(),
                    e.getMessage() != null ? e.getMessage() : "Resume execution failed"
            );
        }
    }
}
