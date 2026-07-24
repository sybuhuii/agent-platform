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
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentFrameworkException.class)
    public ResponseEntity<Map<String, String>> handleAgentFrameworkException(AgentFrameworkException e) {
        log.warn("Agent framework error: errorCode={}, message={}", e.getErrorCode(), e.getMessage());
        HttpStatus httpStatus = mapErrorCodeToHttpStatus(e.getErrorCode());
        return ResponseEntity.status(httpStatus)
                .body(Map.of(
                        "errorCode", e.getErrorCode().name(),
                        "message", e.getMessage() != null ? e.getMessage() : e.getErrorCode().name()
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

    private HttpStatus mapErrorCodeToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            // 400 Bad Request
            case INVALID_ARGUMENT, TOOL_NOT_FOUND, ROLE_NOT_FOUND -> HttpStatus.BAD_REQUEST;

            // 401 Unauthorized
            case AUTHENTICATION_FAILED, INVALID_CREDENTIALS,
                    SESSION_NOT_FOUND, SESSION_INVALID, SESSION_EXPIRED -> HttpStatus.UNAUTHORIZED;

            // 403 Forbidden
            case USER_DISABLED, PERMISSION_DENIED, TOOL_ACCESS_DENIED -> HttpStatus.FORBIDDEN;

            // 404 Not Found
            case AGENT_NOT_FOUND, SUPERVISOR_NOT_FOUND, USER_NOT_FOUND -> HttpStatus.NOT_FOUND;

            // 409 Conflict
            case USER_ALREADY_EXISTS, ROLE_ALREADY_EXISTS, APPROVAL_REQUIRED -> HttpStatus.CONFLICT;

            // 502 Bad Gateway
            case MODEL_INVOCATION_FAILED -> HttpStatus.BAD_GATEWAY;

            // 503 Service Unavailable
            case MODEL_NOT_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;

            // 500 Internal Server Error
            case CHECKPOINT_NOT_FOUND, MAX_ITERATIONS_REACHED,
                    TOOL_EXECUTION_FAILED, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;

            // 默认 500
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
