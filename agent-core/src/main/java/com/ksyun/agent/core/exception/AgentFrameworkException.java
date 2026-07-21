package com.ksyun.agent.core.exception;

/**
 * Agent 框架统一异常。
 */
public class AgentFrameworkException extends RuntimeException {

    private final AgentErrorCode errorCode;

    public AgentFrameworkException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentFrameworkException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AgentErrorCode getErrorCode() {
        return errorCode;
    }
}
