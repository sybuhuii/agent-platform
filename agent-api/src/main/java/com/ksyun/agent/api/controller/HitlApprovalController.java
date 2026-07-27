package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ApprovalDecideRequest;
import com.ksyun.agent.api.dto.ApprovalResumeResponse;
import com.ksyun.agent.api.dto.PendingApprovalSummaryResponse;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.approval.ApprovalDecisionCommand;
import com.ksyun.agent.application.approval.ApprovalResumeApplicationService;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.approval.ApprovalStatus;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.AgentCheckpoint;
import com.ksyun.agent.core.run.CheckpointStatus;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.store.CheckpointStore;
import com.ksyun.agent.core.tool.ToolRiskLevel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * HITL 审批 Controller。
 * <p>
 * 统一路径：/api/agent/approval
 * 受认证保护。操作者身份来自已验证 UserSession。
 * <p>
 * 接口：
 * - GET /pending  查询当前用户待审批记录
 * - POST /decide  审批决定（批准/拒绝）
 * <p>
 * 用户隔离：只返回 userId 精确匹配 + SUSPENDED + PENDING 的记录。
 * 不得根据 username 判断归属。
 * 不同用户不得串读。
 * SUSPENDED 正常返回 200。
 */
@RestController
@RequestMapping("/api/agent/approval")
public class HitlApprovalController {

    private final ObjectProvider<ApprovalResumeApplicationService> serviceProvider;
    private final CheckpointStore checkpointStore;

    public HitlApprovalController(ObjectProvider<ApprovalResumeApplicationService> serviceProvider,
                                    CheckpointStore checkpointStore) {
        this.serviceProvider = serviceProvider;
        this.checkpointStore = checkpointStore;
    }

    /**
     * 查询当前用户待审批记录。
     * <p>
     * 只返回 userId 精确匹配 + SUSPENDED + PENDING。
     * 按 requestedAt 升序。
     * 不得返回 stateData、Session ID、原始工具参数。
     * 不得返回其他用户的记录。
     */
    @GetMapping("/pending")
    public ResponseEntity<?> listPending(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session
    ) {
        String userId = session.userId();

        try {
            Collection<AgentCheckpoint> checkpoints = checkpointStore.findPendingByUserId(userId);

            List<PendingApprovalSummaryResponse> summaries = new ArrayList<>();
            for (AgentCheckpoint cp : checkpoints) {
                if (cp.pendingApproval() == null) continue;

                summaries.add(new PendingApprovalSummaryResponse(
                        cp.runId(),
                        cp.threadId(),
                        cp.pendingApproval().approvalId(),
                        cp.agentName(),
                        cp.pendingApproval().payload().operationName(),
                        cp.pendingApproval().payload().riskLevel(),
                        cp.pendingApproval().status(),
                        cp.pendingApproval().payload().safeArguments(),
                        cp.pendingApproval().payload().requestedAt(),
                        cp.pendingApproval().payload().reason()
                ));
            }

            return ResponseEntity.ok(summaries);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("errorCode", AgentErrorCode.INTERNAL_ERROR.name(),
                            "message", "Failed to query pending approvals"));
        }
    }

    /**
     * 审批决定（批准/拒绝）。
     * <p>
     * APPROVE：记录决定后恢复执行
     * REJECT：记录决定后返回拒绝结果
     * <p>
     * SUSPENDED（恢复后再次挂起）正常返回 200。
     */
    @PostMapping("/decide")
    public ResponseEntity<?> decide(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestBody ApprovalDecideRequest request
    ) {
        ApprovalResumeApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", AgentErrorCode.INTERNAL_ERROR.name(),
                            "message", "Approval service is not available"));
        }

        try {
            ApprovalDecisionCommand command = new ApprovalDecisionCommand(
                    request.runId(),
                    request.approvalId(),
                    request.action(),
                    request.comment()
            );

            AgentResult result = service.decideAndResume(session, command);

            ApprovalResumeResponse response = toResumeResponse(request.runId(), result);
            return ResponseEntity.ok(response);
        } catch (AgentFrameworkException e) {
            return ResponseEntity.status(mapErrorToHttpStatus(e.getErrorCode()))
                    .body(Map.of("errorCode", e.getErrorCode().name(),
                            "message", e.getMessage()));
        }
    }

    private ApprovalResumeResponse toResumeResponse(String runId, AgentResult result) {
        String approvalId = result.metadata() != null
                ? (String) result.metadata().getOrDefault("approvalId", "") : "";
        String operationName = result.metadata() != null
                ? (String) result.metadata().getOrDefault("operationName", "") : "";
        String riskLevelStr = result.metadata() != null
                ? (String) result.metadata().getOrDefault("riskLevel", "") : "";
        ToolRiskLevel riskLevel = parseRiskLevel(riskLevelStr);

        return new ApprovalResumeResponse(
                runId,
                "",  // threadId 从 AgentResult 中不可直接获取
                "",  // agentName 从 AgentResult 中获取
                result.success(),
                result.content(),
                result.errorCode(),
                result.evidence(),
                result.metadata(),
                result.status(),
                approvalId,
                operationName,
                riskLevel
        );
    }

    private ToolRiskLevel parseRiskLevel(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ToolRiskLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private HttpStatus mapErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case CHECKPOINT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case APPROVAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case APPROVAL_ALREADY_DECIDED -> HttpStatus.CONFLICT;
            case RUN_ALREADY_RESUMING -> HttpStatus.CONFLICT;
            case CHECKPOINT_CONFLICT -> HttpStatus.CONFLICT;
            case CHECKPOINT_NOT_RESUMABLE -> HttpStatus.BAD_REQUEST;
            case INVALID_APPROVAL_DECISION -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
