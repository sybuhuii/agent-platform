package com.ksyun.agent.application.agent;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.CheckpointExecutionType;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.run.ThreadIdGenerator;
import com.ksyun.agent.core.security.UserSession;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationCheckpointService;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionCoordinator;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadExecutionLease;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadIdValidator;
import com.ksyun.agent.runtime.react.ReactAgentEngine;
import com.ksyun.agent.runtime.react.ThreadExecutionOutcome;
import com.ksyun.agent.runtime.registry.AgentRegistry;
import com.ksyun.agent.runtime.run.RunIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * 正式认证 Agent 执行服务。
 * <p>
 * session 必须来自已验证入口。不绕过 SessionValidationService。
 * 保持无状态和线程安全。
 * 不调用模型或工具。
 * 不返回 sessionId。
 * AgentResult 为空时转换为 INTERNAL_ERROR。
 * <p>
 * 支持可选 threadId：
 * - threadId 为空时生成新线程
 * - threadId 存在时续接已有线程
 * - 同一 userId 同一 threadId 互斥执行
 * - 挂起线程阻止普通新消息
 * - 正常完成后保存 THREAD_MEMORY
 * - 失败和挂起不覆盖上一轮稳定状态
 */
public class AuthenticatedAgentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedAgentApplicationService.class);

    private final AgentRegistry agentRegistry;
    private final ReactAgentEngine reactAgentEngine;
    private final RunIdGenerator runIdGenerator;
    private final ThreadIdGenerator threadIdGenerator;
    private final ThreadIdValidator threadIdValidator;
    private final ThreadConversationCheckpointService threadConversationCheckpointService;
    private final ThreadExecutionCoordinator threadExecutionCoordinator;

    public AuthenticatedAgentApplicationService(AgentRegistry agentRegistry,
                                                  ReactAgentEngine reactAgentEngine,
                                                  RunIdGenerator runIdGenerator,
                                                  ThreadIdGenerator threadIdGenerator,
                                                  ThreadIdValidator threadIdValidator,
                                                  ThreadConversationCheckpointService threadConversationCheckpointService,
                                                  ThreadExecutionCoordinator threadExecutionCoordinator) {
        this.agentRegistry = agentRegistry;
        this.reactAgentEngine = reactAgentEngine;
        this.runIdGenerator = runIdGenerator;
        this.threadIdGenerator = threadIdGenerator;
        this.threadIdValidator = threadIdValidator;
        this.threadConversationCheckpointService = threadConversationCheckpointService;
        this.threadExecutionCoordinator = threadExecutionCoordinator;
    }

    /**
     * 执行正式认证的单 Agent ReAct 调用（旧方法，保持兼容）。
     * <p>
     * 委托新方法并使用空 threadId。
     *
     * @param session   已验证的用户会话
     * @param agentName Agent 名称
     * @param message   用户消息
     * @return 执行结果
     */
    public AuthenticatedAgentRunResult invoke(UserSession session, String agentName, String message) {
        return invoke(session, agentName, message, Optional.empty());
    }

    /**
     * 执行正式认证的单 Agent ReAct 调用，支持可选 threadId。
     * <p>
     * 执行流程：
     * 1. 基础校验
     * 2. threadId 为空时生成新 threadId
     * 3. threadId 存在时校验并加载已有状态
     * 4. 挂起检查
     * 5. 并发 Lease
     * 6. 生成新的 runId 和 taskId
     * 7. 调用 ReactAgentEngine.executeThread
     * 8. 保存稳定状态
     * 9. 返回结果
     *
     * @param session            已验证的用户会话
     * @param agentName          Agent 名称
     * @param message            用户消息
     * @param requestedThreadId  请求的线程 ID，Optional.empty 表示新线程
     * @return 执行结果
     */
    public AuthenticatedAgentRunResult invoke(UserSession session, String agentName, String message,
                                               Optional<String> requestedThreadId) {
        // ---- 基础校验 ----
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

        // ---- 确定 threadId ----
        String threadId;
        Optional<ThreadConversationState> previousState;

        if (requestedThreadId.isEmpty() || requestedThreadId.get() == null || requestedThreadId.get().isBlank()) {
            // threadId 为空：生成新 threadId
            threadId = threadIdGenerator.generate();
            threadIdValidator.validate(threadId);
            previousState = Optional.empty();
            log.info("New thread: generated threadId={}, userId={}, agent={}", threadId, session.userId(), agentName);
        } else {
            // threadId 存在：校验并加载
            String trimmedThreadId = requestedThreadId.get().trim();
            threadIdValidator.validate(trimmedThreadId);
            threadId = trimmedThreadId;

            // 加载已有状态，userId 来自当前 Session
            previousState = threadConversationCheckpointService.load(
                    session.userId(), threadId, CheckpointExecutionType.REACT_AGENT, agentName);

            if (previousState.isEmpty()) {
                // 不自动创建新线程
                throw new AgentFrameworkException(AgentErrorCode.THREAD_NOT_FOUND,
                        "Thread not found for the specified agent");
            }
        }

        // ---- 并发 Lease（覆盖加载、执行和保存） ----
        ThreadExecutionLease lease = threadExecutionCoordinator.acquire(session.userId(), threadId);
        try {
            return executeWithLease(session, agentName, message, definition, threadId, previousState, lease);
        } finally {
            lease.close();
        }
    }

    /**
     * 在 Lease 保护下执行 Agent 调用。
     * <p>
     * 包含：挂起检查、加载旧状态、执行、保存、返回。
     */
    private AuthenticatedAgentRunResult executeWithLease(
            UserSession session,
            String agentName,
            String message,
            AgentDefinition definition,
            String threadId,
            Optional<ThreadConversationState> previousState,
            ThreadExecutionLease lease
    ) {
        // ---- 挂起检查（Lease 内） ----
        if (threadConversationCheckpointService.hasActiveHitlRun(session.userId(), threadId)) {
            throw new AgentFrameworkException(AgentErrorCode.THREAD_SUSPENDED,
                    "Thread has an active HITL run, please complete approval first");
        }

        // 如果 previousState 为空但 threadId 已存在（续接路径下 Lease 内重新加载）
        // 对于续接路径 previousState 已经在 Lease 外加载，Lease 内此处无需重新加载
        // 对于新线程路径 previousState 为 empty

        // ---- 生成新的 runId 和 taskId ----
        String runId = runIdGenerator.nextRunId();
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

        log.info("AuthenticatedAgent invoke: runId={}, threadId={}, agent={}, userId={}, newThread={}",
                runId, threadId, agentName, session.userId(), previousState.isEmpty());

        // ---- 执行 ----
        ThreadExecutionOutcome outcome = reactAgentEngine.executeThread(definition, task, context, previousState);

        AgentResult result = outcome.result();
        if (result == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR, "Agent execution returned null result");
        }

        // ---- 保存稳定状态 ----
        if (outcome.conversationState().isPresent()) {
            try {
                threadConversationCheckpointService.save(
                        session.userId(), threadId, runId, outcome.conversationState().get());
                log.info("Thread memory saved: runId={}, threadId={}, userId={}", runId, threadId, session.userId());
            } catch (AgentFrameworkException e) {
                log.error("Thread memory save failed: runId={}, threadId={}, errorCode={}",
                        runId, threadId, e.getErrorCode());
                throw e;
            }
        } else {
            log.info("No stable state to save: runId={}, threadId={}, resultStatus={}",
                    runId, threadId, result.status());
        }

        // ---- 返回 ----
        return new AuthenticatedAgentRunResult(runId, threadId, definition.name(), result);
    }
}
