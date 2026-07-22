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
 * 全局异常处理器。
 * <p>
 * SpringAI 底层异常不能原样返回客户端。
 * 客户端响应不得包含堆栈、API Key、baseUrl 中的敏感查询参数或底层实现类。
 * 后端日志记录完整异常堆栈，但不得记录密钥和完整 Prompt。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentFrameworkException.class)
    public ResponseEntity<Map<String, Object>> handleAgentFrameworkException(AgentFrameworkException e) {
        log.warn("Agent framework error: errorCode={}, message={}", e.getErrorCode(), e.getMessage());

        HttpStatus status = mapToHttpStatus(e.getErrorCode());
        return ResponseEntity.status(status)
                .body(Map.of(
                        "errorCode", e.getErrorCode().name(),
                        "message", e.getMessage()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        // 后端日志记录完整异常堆栈，但不得记录密钥和完整 Prompt
        log.error("Unexpected error: {}", e.getMessage(), e);

        // 客户端响应不得包含堆栈或底层实现类
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "errorCode", AgentErrorCode.INTERNAL_ERROR.name(),
                        "message", "An internal error occurred"
                ));
    }

    private HttpStatus mapToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case TOOL_NOT_FOUND -> HttpStatus.BAD_REQUEST;
            case MODEL_INVOCATION_FAILED -> HttpStatus.BAD_GATEWAY;
            case TOOL_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case TOOL_EXECUTION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
