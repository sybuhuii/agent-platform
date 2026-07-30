package com.ksyun.agent.runtime.react;

import com.ksyun.agent.core.agent.AgentDefinition;
import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * 默认 ReAct 执行引擎实现。
 * <p>
 * 单 Agent ReAct 执行入口。不暴露 LangGraph4j 类型。
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * CompiledGraph 生命周期：在构造时编译一次图，保存为 final 字段复用。
 * LangGraph4j 1.8.x CompiledGraph 的节点和边在编译后不可变，
 * invoke 不使用共享可变状态，可安全并发执行。
 * <p>
 * 保证：
 * - 图进入 SUSPEND 后能读取挂起 finalResult
 * - 不会把 SUSPENDED 转换成 FAILED
 * - 不会因为 Checkpoint 存在而自动恢复
 * - 不会删除 Checkpoint
 * - 不会继续执行后续节点
 * - 不会再次调用模型
 * - 不会再次执行危险工具
 * - 普通 COMPLETE、FAIL 和 MAX_ITERATIONS 行为不变
 * <p>
 * executeThread 新增线程续接支持：
 * - previousState 为空时通过 Mapper 创建新 State
 * - previousState 存在时通过 Mapper 创建续接 State
 * - 执行完成后调用 PersistencePolicy 判断是否稳定
 * - 稳定时通过 Mapper 提取 ThreadConversationState
 * - 不在 Engine 中访问 CheckpointStore 或保存 THREAD_MEMORY
 */
public class DefaultReactAgentEngine implements ReactAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultReactAgentEngine.class);

    private final ReactExecutionValidator validator;
    private final CompiledGraph<ReactAgentState> compiledGraph;
    private final ReactThreadConversationStateMapper stateMapper;
    private final ReactThreadPersistencePolicy persistencePolicy;
    private final java.time.Clock clock;

    public DefaultReactAgentEngine(ReactExecutionValidator validator,
                                    ReactAgentGraphFactory graphFactory,
                                    ReactThreadConversationStateMapper stateMapper,
                                    ReactThreadPersistencePolicy persistencePolicy,
                                    java.time.Clock clock) {
        this.validator = validator;
        this.compiledGraph = graphFactory.buildGraph();
        this.stateMapper = stateMapper;
        this.persistencePolicy = persistencePolicy;
        this.clock = clock;
    }

    /**
     * 兼容旧调用方，委托 executeThread 并只返回 result。
     * <p>
     * 不访问 CheckpointStore。不保存 THREAD_MEMORY。
     */
    @Override
    public AgentResult execute(AgentDefinition definition, AgentTask task, RunContext context) {
        return executeThread(definition, task, context, Optional.empty()).result();
    }

    /**
     * 执行线程级别的 Agent ReAct 循环。
     * <p>
     * 流程：
     * 1. 校验请求参数
     * 2. previousState 为空时创建新 State
     * 3. previousState 存在时创建续接 State
     * 4. 调用现有 CompiledGraph
     * 5. 取得最终 ReactAgentState
     * 6. 取得 AgentResult
     * 7. 调用持久化策略判断是否稳定
     * 8. 稳定时提取 ThreadConversationState
     * 9. 不稳定时 conversationState 为空
     * 10. 返回 ThreadExecutionOutcome
     * <p>
     * 不在 Engine 中访问 CheckpointStore。
     * 不在 Engine 中保存 THREAD_MEMORY。
     * 不在 Engine 中访问 SessionStore。
     * 不在 Engine 中生成 threadId 或 runId。
     */
    @Override
    public ThreadExecutionOutcome executeThread(
            AgentDefinition definition,
            AgentTask task,
            RunContext context,
            Optional<ThreadConversationState> previousState
    ) {
        // 1. 校验请求参数
        validator.validate(definition, task, context);

        // 2. 构造初始/续接 State
        ReactAgentState initialState;
        if (previousState.isEmpty()) {
            initialState = stateMapper.createInitialState(definition, task, context);
            log.info("New thread execution: runId={}, threadId={}, agent={}",
                    context.runId(), context.threadId(), definition.name());
        } else {
            initialState = stateMapper.createContinuedState(
                    definition, task, context, previousState.get());
            log.info("Continued thread execution: runId={}, threadId={}, agent={}, previousMessageCount={}",
                    context.runId(), context.threadId(), definition.name(),
                    previousState.get().messages().size());
        }

        // 3. 执行图
        ReactAgentState finalState;
        try {
            finalState = compiledGraph.invoke(initialState.data())
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Graph execution returned empty state"));
        } catch (AgentFrameworkException e) {
            log.error("ReAct execution failed: runId={}, agent={}, errorCode={}",
                    context.runId(), definition.name(), e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.error("ReAct execution unexpected error: runId={}, agent={}",
                    context.runId(), definition.name(), e);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "ReAct execution failed",
                    e
        );
        }

        // 4. 读取最终结果
        AgentResult finalResult = ReactStateKeys.getFinalResult(finalState);
        if (finalResult == null) {
            log.error("ReAct execution completed without finalResult: runId={}, agent={}",
                    context.runId(), definition.name());
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "ReAct execution completed without result"
            );
        }

        // 5. 判断是否可持久化
        boolean persistable = persistencePolicy.isPersistable(finalResult, finalState);

        // 6. 稳定时提取 ThreadConversationState
        Optional<ThreadConversationState> conversationState;
        if (persistable) {
            try {
                Instant updatedAt = clock.instant();
                ThreadConversationState extracted = stateMapper.extractStableState(
                        definition.name(), context.runId(), finalState, updatedAt);
                conversationState = Optional.of(extracted);
                log.info("Stable state extracted: runId={}, threadId={}, agent={}, messageCount={}",
                        context.runId(), context.threadId(), definition.name(),
                        extracted.messages().size());
            } catch (AgentFrameworkException e) {
                log.error(
                        "Stable state extraction failed: "
                                + "runId={}, threadId={}, errorCode={}",
                        context.runId(),
                        context.threadId(),
                        e.getErrorCode());
                throw e;
            }
        } else {
            conversationState = Optional.empty();
            log.info("State not persistable: runId={}, threadId={}, status={}",
                    context.runId(), context.threadId(), finalResult.status());
        }

        // SUSPENDED 不转换成 FAILED
        return new ThreadExecutionOutcome(finalResult, conversationState);
    }
}
