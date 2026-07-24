package com.ksyun.agent.application.agent;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 正式认证 Agent 执行服务。
 * <p>
 * session 必须来自已验证入口。不绕过 SessionValidationService。
 * 保持无状态和线程安全。
 * 不调用模型或工具。
 * 不返回 sessionId。
 * AgentResult 为空时转换为 INTERNAL_ERROR。
 */
public class AuthenticatedAgentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedAgentApplicationService.class);

    private final AgentRegistry agentRegistry;
    private final ReactAgentEngine reactAgentEngine;
    private final RunIdGenerator runIdGenerator;

    public AuthenticatedAgentApplicationService(AgentRegistry agentRegistry,
                                                  ReactAgentEngine reactAgentEngine,
                                                  RunIdGenerator runIdGenerator) {
        this.agentRegistry = agentRegistry;
        this.reactAgentEngine = reactAgentEngine;
        this.runIdGenerator = runIdGenerator;
    }

    /**
     * 执行正式认证的单 Agent ReAct 调用。
     *
     * @param session   已验证的用户会话
     * @param agentName Agent 名称
     * @param message   用户消息
     * @return 执行结果
     */
    public AuthenticatedAgentRunResult invoke(UserSession session, String agentName, String message) {
        if (session == null) {
            throw new AgentFrameworkException(AgentErrorCode.SESSION_INVALID, "session must not be null");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "agentName must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "message must not be blank");
        }

        AgentDefinition definition = agentRegistry.getRequired(agentName);

        String runId = runIdGenerator.nextRunId();
        String threadId = "user-" + session.userId() + "-agent-thread-" + runId;
        String taskId = "agent-task-" + runId;

        AgentTask task = new AgentTask(taskId, definition.name(), message, Map.of());

        RunContext context = new RunContext(
                session.userId(),
                session.sessionId(),
                threadId,
                runId,
                session.roles(),
                session.permissions()
        );

        log.info("AuthenticatedAgent invoke: runId={}, agent={}, userId={}", runId, agentName, session.userId());

        AgentResult result = reactAgentEngine.execute(definition, task, context);

        if (result == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR, "Agent execution returned null result");
        }

        return new AuthenticatedAgentRunResult(runId, threadId, definition.name(), result);
    }
}
