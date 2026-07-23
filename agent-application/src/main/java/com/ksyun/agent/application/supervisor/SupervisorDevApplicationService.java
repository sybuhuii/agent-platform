package com.ksyun.agent.application.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.registry.SupervisorRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import com.ksyun.agent.runtime.supervisor.SupervisorEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 开发环境 Supervisor 多Agent执行服务。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 * 保持无状态和线程安全，每次请求生成独立 runId/threadId/taskId。
 */
public class SupervisorDevApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorDevApplicationService.class);

    private static final String DEV_USER_ID = "dev-user";
    private static final String DEV_SESSION_ID = "dev-session";

    private final SupervisorRegistry supervisorRegistry;
    private final SupervisorEngine supervisorEngine;
    private final RunIdGenerator runIdGenerator;

    public SupervisorDevApplicationService(SupervisorRegistry supervisorRegistry,
                                             SupervisorEngine supervisorEngine,
                                             RunIdGenerator runIdGenerator) {
        this.supervisorRegistry = supervisorRegistry;
        this.supervisorEngine = supervisorEngine;
        this.runIdGenerator = runIdGenerator;
    }

    /**
     * 执行一次开发环境 Supervisor 多Agent调用。
     *
     * @param supervisorName Supervisor 名称，非空
     * @param message        用户消息，非空
     * @return 执行结果
     */
    public SupervisorDevRunResult invoke(String supervisorName, String message) {
        if (supervisorName == null || supervisorName.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "supervisorName must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "message must not be blank");
        }

        SupervisorDefinition definition = supervisorRegistry.getRequired(supervisorName);

        String runId = runIdGenerator.nextRunId();
        String threadId = "dev-supervisor-thread-" + runId;
        String taskId = "dev-supervisor-task-" + runId;

        AgentTask rootTask = new AgentTask(taskId, definition.name(), message, Map.of());

        RunContext context = new RunContext(
                DEV_USER_ID,
                DEV_SESSION_ID,
                threadId,
                runId,
                Set.of(),
                Set.of()
        );

        log.info("SupervisorDev invoke: runId={}, supervisor={}, threadId={}", runId, supervisorName, threadId);

        AgentResult result = supervisorEngine.execute(definition, rootTask, context);

        if (result == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR, "Supervisor execution returned null result");
        }

        return new SupervisorDevRunResult(runId, threadId, definition.name(), result);
    }
}
