package com.ksyun.agent.application.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * 开发环境单 Agent ReAct 执行服务。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 * 保持无状态和线程安全，每次请求生成独立 runId/threadId/taskId。
 */
public class ReactDevApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReactDevApplicationService.class);

    private static final String DEV_USER_ID = "dev-user";
    private static final String DEV_SESSION_ID = "dev-session";

    private final AgentRegistry agentRegistry;
    private final ReactAgentEngine reactAgentEngine;
    private final RunIdGenerator runIdGenerator;

    public ReactDevApplicationService(AgentRegistry agentRegistry,
                                       ReactAgentEngine reactAgentEngine,
                                       RunIdGenerator runIdGenerator) {
        this.agentRegistry = agentRegistry;
        this.reactAgentEngine = reactAgentEngine;
        this.runIdGenerator = runIdGenerator;
    }

    /**
     * 执行一次开发环境单 Agent ReAct 调用。
     *
     * @param agentName Agent 名称，非空
     * @param message   用户消息，非空
     * @return 执行结果
     */
    public ReactDevRunResult invoke(String agentName, String message) {
        if (agentName == null || agentName.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "agentName must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new AgentFrameworkException(AgentErrorCode.INVALID_ARGUMENT, "message must not be blank");
        }

        AgentDefinition definition = agentRegistry.getRequired(agentName);

        String runId = runIdGenerator.nextRunId();
        String threadId = "dev-thread-" + runId;
        String taskId = "dev-task-" + runId;

        AgentTask task = new AgentTask(taskId, definition.name(), message, Map.of());

        RunContext context = new RunContext(
                DEV_USER_ID,
                DEV_SESSION_ID,
                threadId,
                runId,
                Set.of(),
                Set.of()
        );

        log.info("ReactDev invoke: runId={}, agent={}, threadId={}", runId, agentName, threadId);

        AgentResult result = reactAgentEngine.execute(definition, task, context);

        if (result == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR, "React execution returned null result");
        }

        return new ReactDevRunResult(runId, threadId, definition.name(), result);
    }
}
