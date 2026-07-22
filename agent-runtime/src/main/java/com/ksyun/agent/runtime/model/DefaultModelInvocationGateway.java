package com.ksyun.agent.runtime.model;

import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.model.ModelClient;
import com.ksyun.agent.core.model.ModelRequest;
import com.ksyun.agent.core.model.ModelResponse;
import com.ksyun.agent.core.run.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 默认模型调用网关实现。
 * <p>
 * 校验入参，调用 ModelClient，记录安全日志。
 * 保持纯 Java 实现，不添加 Spring 注解。
 */
public class DefaultModelInvocationGateway implements ModelInvocationGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelInvocationGateway.class);

    private final ModelClient modelClient;

    public DefaultModelInvocationGateway(ModelClient modelClient) {
        if (modelClient == null) {
            throw new IllegalArgumentException("ModelClient must not be null");
        }
        this.modelClient = modelClient;
    }

    @Override
    public ModelResponse invoke(ModelRequest request, RunContext context) {
        validate(request, context);

        String runId = context.runId();
        long threadId = Thread.currentThread().getId();
        String userId = context.userId();
        int messageCount = request.messages().size();
        int toolCount = request.tools().size();
        Instant startTime = Instant.now();

        try {
            ModelResponse response = modelClient.generate(request);
            long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();

            if (response == null) {
                logModelCall(runId, threadId, userId, messageCount, toolCount,
                        startTime, durationMs, false, AgentErrorCode.MODEL_INVOCATION_FAILED.name());
                throw new AgentFrameworkException(
                        AgentErrorCode.MODEL_INVOCATION_FAILED,
                        "ModelClient returned null response"
                );
            }

            logModelCall(runId, threadId, userId, messageCount, toolCount,
                    startTime, durationMs, true, null);
            return response;
        } catch (AgentFrameworkException e) {
            long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            logModelCall(runId, threadId, userId, messageCount, toolCount,
                    startTime, durationMs, false, e.getErrorCode().name());
            throw e;
        } catch (Exception e) {
            long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            logModelCall(runId, threadId, userId, messageCount, toolCount,
                    startTime, durationMs, false, AgentErrorCode.MODEL_INVOCATION_FAILED.name());
            log.error("Model invocation failed: runId={}, userId={}", runId, userId, e);
            throw new AgentFrameworkException(
                    AgentErrorCode.MODEL_INVOCATION_FAILED,
                    "Model invocation failed due to an internal error"
            );
        }
    }

    private void validate(ModelRequest request, RunContext context) {
        if (request == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT, "ModelRequest must not be null"
            );
        }
        if (context == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT, "RunContext must not be null"
            );
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.INVALID_ARGUMENT, "Messages must not be null or empty"
            );
        }
    }

    private void logModelCall(String runId, long threadId, String userId,
                              int messageCount, int toolCount,
                              Instant startTime, long durationMs,
                              boolean success, String errorCode) {
        log.info("ModelCall runId={} threadId={} userId={} messages={} tools={} "
                        + "startTime={} durationMs={} success={} errorCode={}",
                runId, threadId, userId, messageCount, toolCount,
                startTime, durationMs, success, errorCode);
    }
}
