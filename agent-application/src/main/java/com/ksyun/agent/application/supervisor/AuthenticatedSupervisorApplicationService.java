package com.ksyun.agent.application.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.supervisor.SupervisorEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 正式认证 Supervisor 执行服务。
 * <p>
 * session 必须来自已验证入口。不绕过 SessionValidationService。
 * 保持无状态和线程安全。
 * 不直接调用 ReactAgentEngine。
 * 不直接调用模型或工具。
 * 不自行实现 Supervisor 循环。
 * 不返回 sessionId。
 * AgentResult 为空时转换为 INTERNAL_ERROR。
 */
public class AuthenticatedSupervisorApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedSupervisorApplicationService.class);

    private final SupervisorRegistry supervisorRegistry;
    private final SupervisorEngine supervisorEngine;
    private final RunIdGenerator runIdGenerator;

    public AuthenticatedSupervisorApplicationService(SupervisorRegistry supervisorRegistry,
                                                       SupervisorEngine supervisorEngine,
                                                       RunIdGenerator runIdGenerator) {
        this.supervisorRegistry = supervisorRegistry;
        this.supervisorEngine = supervisorEngine;
        this.runIdGenerator = runIdGenerator;
    }

    /**
     * 执行正式认证的 Supervisor 多Agent调用。
     *
     * @param session         已验证的用户会话
     * @param supervisorName  Supervisor 名称
     * @param message         用户消息
     * @return 执行结果
     */
    public AuthenticatedSupervisorRunResult invoke(UserSession session, String supervisorName, String message) {
        if (session == null) {
            throw new AgentFrameworkException(AgentErrorCode.SESSION_INVALID, "session must not be null");
        }
        if (supervisorName == null || supervisorName.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "supervisorName must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "message must not be blank");
        }

        SupervisorDefinition definition = supervisorRegistry.getRequired(supervisorName);

        String runId = runIdGenerator.nextRunId();
        String threadId = "user-" + session.userId() + "-supervisor-thread-" + runId;
        String taskId = "supervisor-task-" + runId;

        AgentTask rootTask = new AgentTask(taskId, definition.name(), message, Map.of());

        RunContext context = new RunContext(
                session.userId(),
                session.sessionId(),
                threadId,
                runId,
                session.roles(),
                session.permissions()
        );

        log.info("AuthenticatedSupervisor invoke: runId={}, supervisor={}, userId={}", runId, supervisorName, session.userId());

        AgentResult result = supervisorEngine.execute(definition, rootTask, context);

        if (result == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR, "Supervisor execution returned null result");
        }

        return new AuthenticatedSupervisorRunResult(runId, threadId, definition.name(), result);
    }
}
