package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ApprovalDecisionRequest;
import com.ksyun.agent.api.dto.ApprovalResumeResponse;
import com.ksyun.agent.api.dto.PendingApprovalDetailResponse;
import com.ksyun.agent.api.dto.PendingApprovalSummaryResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.approval.ApprovalDecisionCommand;
import com.ksyun.agent.application.approval.ApprovalResumeApplicationService;
import com.ksyun.agent.application.approval.PendingApprovalDetail;
import com.ksyun.agent.application.approval.PendingApprovalSummary;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.application.approval.PendingApprovalQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * Controller 只依赖 Application Service 和查询服务。
 * 不得直接访问 CheckpointStore。
 * 不得直接调用 ApprovalDecisionService 和 ReactResumeEngine 分别拼接业务流程。
 * 不得自行构造 ReactAgentState。
 * 不得提供裸 resume 接口。
 * 保持薄层：只做 DTO 转换和错误映射。
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
     * <p>
     * 只返回 userId 精确匹配 + SUSPENDED + PENDING。
     * 按 requestedAt 升序。
     * 不返回 stateData、sessionId、原始工具参数。
     * 不返回其他用户的记录。
     */
    @GetMapping
    public ResponseEntity<?> listPending(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        PendingApprovalQueryService queryService = queryServiceProvider.getIfAvailable();
        if (queryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.INTERNAL_ERROR.name(),
                            "message", "Approval query service is not available"));
        }

        try {
            Collection<PendingApprovalSummary> summaries = queryService.findPending(session);

            List<PendingApprovalSummaryResponse> responses = summaries.stream()
                    .map(this::toSummaryResponse)
                    .toList();

            return ResponseEntity.ok(responses);
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    /**
     * 查询指定 runId 的审批详情。
     * <p>
     * 其他用户 runId 按安全 404 处理。
     * 不返回 stateData、原始参数或指纹。
     */
    @GetMapping("/{runId}")
    public ResponseEntity<?> getPending(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String runId
    ) {
        PendingApprovalQueryService queryService = queryServiceProvider.getIfAvailable();
        if (queryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.INTERNAL_ERROR.name(),
                            "message", "Approval query service is not available"));
        }

        try {
            PendingApprovalDetail detail = queryService.getPending(session, runId);
            PendingApprovalDetailResponse response = toDetailResponse(detail);
            return ResponseEntity.ok(response);
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    /**
     * 审批决定并恢复执行。
     * <p>
     * runId 来自路径。Body 只包含 approvalId、action、comment。
     * 批准和拒绝共用同一接口。
     * 再次挂起正常返回 SUSPENDED 和新 approvalId。
     * 重复提交由后端幂等和 version 控制。
     * <p>
     * 禁止：
     * - Controller 直接访问 CheckpointStore
     * - Controller 直接调用 DecisionService 和 ResumeEngine
     * - Controller 自行构造 ReactAgentState
     * - 提供裸 resume 接口
     */
    @PostMapping("/{runId}/decide-and-resume")
    public ResponseEntity<?> decideAndResume(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String runId,
            @RequestBody ApprovalDecisionRequest request
    ) {
        ApprovalResumeApplicationService resumeService = resumeServiceProvider.getIfAvailable();
        if (resumeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.MODEL_NOT_AVAILABLE.name(),
                            "message", "Model is not available for resume execution"));
        }

        // runId 来自路径
        ApprovalDecisionCommand command = new ApprovalDecisionCommand(
                runId,
                request.approvalId(),
                request.action(),
                request.trimmedComment()
        );

        try {
            AgentResult result = resumeService.decideAndResume(session, command);

            log.info("HITL decide-and-resume: operatorUserId={}, runId={}, action={}, approvalId={}, resultStatus={}",
                    session.userId(), runId, command.action(), request.approvalId(), result.status());

            ApprovalResumeResponse response = ApprovalResumeResponse.from(
                    runId, "", result);

            return ResponseEntity.ok(response);
        } catch (AgentFrameworkException e) {
            log.warn("HITL decide-and-resume failed: operatorUserId={}, runId={}, errorCode={}",
                    session.userId(), runId, e.getErrorCode());

            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
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

    // ---- 错误码 HTTP 映射 ----

    private HttpStatus mapErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            // 400
            case INVALID_ARGUMENT, INVALID_APPROVAL_DECISION, CHECKPOINT_NOT_RESUMABLE,
                 APPROVAL_REQUIRED -> HttpStatus.BAD_REQUEST;
            // 401
            case AUTHENTICATION_FAILED, INVALID_CREDENTIALS, SESSION_NOT_FOUND,
                 SESSION_INVALID, SESSION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            // 404 - 安全 NOT_FOUND（不泄漏其他用户数据）
            case CHECKPOINT_NOT_FOUND, APPROVAL_NOT_FOUND, AGENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            // 409
            case APPROVAL_ALREADY_DECIDED, CHECKPOINT_CONFLICT, RUN_ALREADY_RESUMING -> HttpStatus.CONFLICT;
            // 503
            case MODEL_NOT_AVAILABLE, MODEL_INVOCATION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            // 500
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
