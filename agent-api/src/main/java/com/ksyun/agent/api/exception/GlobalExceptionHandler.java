package com.ksyun.agent.api.exception;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理器，将 AgentFrameworkException 映射为 HTTP 状态码。
 * <p>
 * 映射规则：
 * 400: INVALID_ARGUMENT, INVALID_APPROVAL_DECISION, CHECKPOINT_NOT_RESUMABLE, APPROVAL_REQUIRED, INVALID_THREAD_ID
 * 401: AUTHENTICATION_FAILED, INVALID_CREDENTIALS, SESSION_NOT_FOUND, SESSION_INVALID, SESSION_EXPIRED
 * 403: USER_DISABLED, PERMISSION_DENIED, TOOL_ACCESS_DENIED
 * 404: AGENT_NOT_FOUND, SUPERVISOR_NOT_FOUND, USER_NOT_FOUND, CHECKPOINT_NOT_FOUND, APPROVAL_NOT_FOUND, THREAD_NOT_FOUND, THREAD_PARTICIPANT_MISMATCH
 * 409: USER_ALREADY_EXISTS, ROLE_ALREADY_EXISTS, APPROVAL_ALREADY_DECIDED, CHECKPOINT_CONFLICT, RUN_ALREADY_RESUMING, THREAD_BUSY, THREAD_SUSPENDED
 * 502: MODEL_INVOCATION_FAILED
 * 503: MODEL_NOT_AVAILABLE
 * 500: MAX_ITERATIONS_REACHED, TOOL_EXECUTION_FAILED, RESUME_FAILED, INTERNAL_ERROR, default
 * <p>
 * 审批拒绝本身不是 HTTP 错误。
 * REJECT 请求可正常返回恢复后的 AgentResult。
 * 再次 SUSPENDED 不是 HTTP 错误。
 * 不暴露堆栈、内部类名、文件路径或 stateData。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentFrameworkException.class)
    public ResponseEntity<Map<String, String>> handleAgentFrameworkException(AgentFrameworkException e) {
        // 正常审批事件不记 ERROR；版本冲突可记 WARN；真实框架失败才记 ERROR
        AgentErrorCode code = e.getErrorCode();
        if (code == AgentErrorCode.CHECKPOINT_CONFLICT
                || code == AgentErrorCode.RUN_ALREADY_RESUMING
                || code == AgentErrorCode.APPROVAL_ALREADY_DECIDED) {
            log.warn("Agent framework conflict: errorCode={}, message={}", code, e.getMessage());
        } else if (code == AgentErrorCode.INTERNAL_ERROR
                || code == AgentErrorCode.RESUME_FAILED
                || code == AgentErrorCode.MODEL_INVOCATION_FAILED) {
            log.error("Agent framework error: errorCode={}, message={}", code, e.getMessage(), e);
        } else {
            log.warn("Agent framework event: errorCode={}, message={}", code, e.getMessage());
        }

        HttpStatus httpStatus = mapErrorCodeToHttpStatus(code);
        // 不返回堆栈、内部类名和 stateData
        return ResponseEntity.status(httpStatus)
                .body(Map.of(
                        "errorCode", code.name(),
                        "message", safeMessage(code, e.getMessage())
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "errorCode", AgentErrorCode.INTERNAL_ERROR.name(),
                        "message", "An internal error occurred"
                ));
    }

    private String safeMessage(
            AgentErrorCode errorCode,
            String originalMessage
    ) {
        return switch (errorCode) {
            case THREAD_NOT_FOUND,
                 THREAD_PARTICIPANT_MISMATCH ->
                    "Thread not found";

            case THREAD_CHECKPOINT_INVALID ->
                    "Thread checkpoint is invalid";

            case MEMORY_STORE_FAILED ->
                    "Memory operation failed";

            case CONTEXT_BUDGET_EXCEEDED,
                 TOKEN_COUNT_FAILED,
                 CONTEXT_PROCESSING_FAILED,
                 CONTEXT_SUMMARY_FAILED,
                 INVALID_CONTEXT_SUMMARY_OUTPUT,
                 INVALID_CONTEXT_WINDOW_STATE ->
                    "Context processing failed";

            case INTERNAL_ERROR,
                 RESUME_FAILED,
                 TOOL_EXECUTION_FAILED ->
                    "An internal error occurred";

            case MODEL_INVOCATION_FAILED ->
                    "Model invocation failed";

            default ->
                    originalMessage != null
                            ? originalMessage
                            : errorCode.name();
        };
    }

    private HttpStatus mapErrorCodeToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            // 400 Bad Request
            case INVALID_ARGUMENT,
                 INVALID_MEMORY_ENTRY,
                 INVALID_CONTEXT_CONFIGURATION,
                 TOOL_NOT_FOUND,
                 ROLE_NOT_FOUND,
                 INVALID_APPROVAL_DECISION,
                 CHECKPOINT_NOT_RESUMABLE,
                 APPROVAL_REQUIRED,
                 INVALID_THREAD_ID -> HttpStatus.BAD_REQUEST;

            // 401 Unauthorized
            case AUTHENTICATION_FAILED, INVALID_CREDENTIALS,
                 SESSION_NOT_FOUND, SESSION_INVALID, SESSION_EXPIRED -> HttpStatus.UNAUTHORIZED;

            // 403 Forbidden
            case USER_DISABLED, PERMISSION_DENIED, TOOL_ACCESS_DENIED -> HttpStatus.FORBIDDEN;

            // 404 Not Found (安全 NOT_FOUND，不泄漏其他用户数据)
            case AGENT_NOT_FOUND, SUPERVISOR_NOT_FOUND, USER_NOT_FOUND,
                 CHECKPOINT_NOT_FOUND, APPROVAL_NOT_FOUND,
                 THREAD_NOT_FOUND, THREAD_PARTICIPANT_MISMATCH -> HttpStatus.NOT_FOUND;

            // 409 Conflict
            case USER_ALREADY_EXISTS, ROLE_ALREADY_EXISTS,
                 APPROVAL_ALREADY_DECIDED, CHECKPOINT_CONFLICT, RUN_ALREADY_RESUMING,
                 THREAD_BUSY, THREAD_SUSPENDED -> HttpStatus.CONFLICT;

            // 502 Bad Gateway
            case MODEL_INVOCATION_FAILED,
                 INVALID_SUPERVISOR_DECISION -> HttpStatus.BAD_GATEWAY;

            // 503 Service Unavailable
            case MODEL_NOT_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;

            case MAX_ITERATIONS_REACHED,
                 TOOL_EXECUTION_FAILED,
                 RESUME_FAILED,
                 THREAD_CHECKPOINT_INVALID,
                 MEMORY_STORE_FAILED,
                 CONTEXT_BUDGET_EXCEEDED,
                 TOKEN_COUNT_FAILED,
                 INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;

            // 默认 500
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
