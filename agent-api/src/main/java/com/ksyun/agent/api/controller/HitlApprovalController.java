package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ApprovalDecisionRequest;
import com.ksyun.agent.api.dto.ApprovalResumeResponse;
import com.ksyun.agent.api.dto.PendingApprovalDetailResponse;
import com.ksyun.agent.api.dto.PendingApprovalSummaryResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.approval.ApprovalDecisionCommand;
import com.ksyun.agent.application.approval.ApprovalResumeApplicationService;
import com.ksyun.agent.application.approval.ApprovalResumeResult;
import com.ksyun.agent.application.approval.PendingApprovalDetail;
import com.ksyun.agent.application.approval.PendingApprovalSummary;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.application.approval.PendingApprovalQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

/**
 * HITL 审批 Controller。
 * <p>
 * 统一路径：/api/hitl
 * 受认证保护。操作者身份来自已验证 UserSession。
 * <p>
 * 接口：
 * - GET  /api/hitl/approvals           查询当前用户待审批列表
 * - GET  /api/hitl/approvals/{runId}   查询指定审批详情
 * - POST /api/hitl/approvals/{runId}/decide-and-resume  审批决定并恢复
 * <p>
 * Controller 薄层：
 * - 只读取已验证 UserSession
 * - 构造命令
 * - 调用 Application Service
 * - 映射成功 DTO
 * - AgentFrameworkException 统一交给 GlobalExceptionHandler
 * - 不直接维护 mapErrorToHttpStatus
 * - 不重复构造错误 JSON
 * - 无模型时通过结构化异常进入统一 503
 * - 不直接访问 CheckpointStore、ResumeEngine 或 CompiledGraph
 */
@RestController
@RequestMapping("/api/hitl/approvals")
public class HitlApprovalController {

    private static final Logger log = LoggerFactory.getLogger(HitlApprovalController.class);

    private final ObjectProvider<PendingApprovalQueryService> queryServiceProvider;
    private final ObjectProvider<ApprovalResumeApplicationService> resumeServiceProvider;

    public HitlApprovalController(
            ObjectProvider<PendingApprovalQueryService> queryServiceProvider,
            ObjectProvider<ApprovalResumeApplicationService> resumeServiceProvider) {
        this.queryServiceProvider = queryServiceProvider;
        this.resumeServiceProvider = resumeServiceProvider;
    }

    /**
     * 查询当前用户待审批列表。
     */
    @GetMapping
    public Collection<PendingApprovalSummaryResponse> listPending(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        PendingApprovalQueryService queryService = queryServiceProvider.getIfAvailable();
        if (queryService == null) {
            throw new AgentFrameworkException(com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    "Approval query service is not available");
        }

        Collection<PendingApprovalSummary> summaries = queryService.findPending(session);
        return summaries.stream().map(this::toSummaryResponse).toList();
    }

    /**
     * 查询指定 runId 的审批详情。
     */
    @GetMapping("/{runId}")
    public PendingApprovalDetailResponse getPending(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String runId
    ) {
        PendingApprovalQueryService queryService = queryServiceProvider.getIfAvailable();
        if (queryService == null) {
            throw new AgentFrameworkException(com.ksyun.agent.core.exception.AgentErrorCode.INTERNAL_ERROR,
                    "Approval query service is not available");
        }

        PendingApprovalDetail detail = queryService.getPending(session, runId);
        return toDetailResponse(detail);
    }

    /**
     * 审批决定并恢复执行。
     * <p>
     * runId 来自路径。Body 只包含 approvalId、action、comment。
     * AgentFrameworkException 统一交给 GlobalExceptionHandler 处理。
     */
    @PostMapping("/{runId}/decide-and-resume")
    public ApprovalResumeResponse decideAndResume(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String runId,
            @RequestBody ApprovalDecisionRequest request
    ) {
        ApprovalResumeApplicationService resumeService = resumeServiceProvider.getIfAvailable();
        if (resumeService == null) {
            throw new AgentFrameworkException(com.ksyun.agent.core.exception.AgentErrorCode.MODEL_NOT_AVAILABLE,
                    "Model is not available for resume execution");
        }

        ApprovalDecisionCommand command = new ApprovalDecisionCommand(
                runId,
                request.approvalId(),
                request.action(),
                request.trimmedComment()
        );

        ApprovalResumeResult resumeResult = resumeService.decideAndResume(session, command);

        log.info("HITL decide-and-resume: runId={}, approvalId={}, action={}, resultStatus={}, parentRunId={}",
                runId, request.approvalId(), request.action(), resumeResult.status(), resumeResult.parentRunId());

        return toResumeResponse(resumeResult);
    }

    // ---- DTO 转换 ----

    private PendingApprovalSummaryResponse toSummaryResponse(PendingApprovalSummary summary) {
        return new PendingApprovalSummaryResponse(
                summary.runId(),
                summary.threadId(),
                summary.agentName(),
                summary.approvalId(),
                summary.operationType(),
                summary.operationName(),
                summary.riskLevel(),
                summary.reason(),
                summary.requestedAt(),
                summary.status()
        );
    }

    private PendingApprovalDetailResponse toDetailResponse(PendingApprovalDetail detail) {
        return new PendingApprovalDetailResponse(
                detail.runId(),
                detail.threadId(),
                detail.agentName(),
                detail.approvalId(),
                detail.operationType(),
                detail.operationName(),
                detail.riskLevel(),
                detail.reason(),
                detail.requestedAt(),
                detail.status(),
                detail.nodeName(),
                detail.safeArguments(),
                detail.createdAt(),
                detail.updatedAt(),
                detail.checkpointVersion()
        );
    }

    private ApprovalResumeResponse toResumeResponse(ApprovalResumeResult result) {
        boolean isNested = result.parentRunId() != null;

        // approvalRunId: 嵌套时使用统一的 safeMetadata 字段，独立时使用 runId
        String approvalRunId;
        if (isNested) {
            // 嵌套 Supervisor 恢复：前端下次审批时需要使用子 Agent 的 runId
            Object approvalRunIdValue = result.safeMetadata().get("approvalRunId");
            approvalRunId = approvalRunIdValue != null ? String.valueOf(approvalRunIdValue) : null;
        } else {
            // 独立 React Agent 恢复：runId 本身就是子 Checkpoint runId
            approvalRunId = result.runId();
        }

        return new ApprovalResumeResponse(
                result.runId(),
                result.threadId(),
                result.agentName(),
                result.success(),
                result.content(),
                result.errorCode(),
                result.evidence(),
                result.status(),
                result.approvalId(),
                result.operationName(),
                result.riskLevel(),
                result.safeMetadata(),
                approvalRunId,
                result.parentRunId(),
                isNested
        );
    }
}
