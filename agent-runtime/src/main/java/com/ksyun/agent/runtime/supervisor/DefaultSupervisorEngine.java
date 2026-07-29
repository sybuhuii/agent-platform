package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.run.RunContext;
import com.ksyun.agent.core.supervisor.SupervisorDefinition;
import com.ksyun.agent.runtime.checkpoint.thread.ThreadConversationState;
import com.ksyun.agent.runtime.react.ThreadExecutionOutcome;
import org.bsc.langgraph4j.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 默认 Supervisor 执行引擎实现。
 * <p>
 * 纯 Java 实现，不添加 Spring 注解。
 * <p>
 * CompiledGraph 生命周期：在构造时编译一次图，保存为 final 字段复用。
 * LangGraph4j 1.8.x CompiledGraph 的节点和边在编译后不可变，
 * invoke 不使用共享可变状态，可安全并发执行。
 */
public class DefaultSupervisorEngine implements SupervisorEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultSupervisorEngine.class);

    private final SupervisorExecutionValidator validator;
    private final SupervisorPromptBuilder promptBuilder;
    private final CompiledGraph<SupervisorAgentState> compiledGraph;
    private final SupervisorThreadConversationStateMapper stateMapper;
    private final SupervisorThreadPersistencePolicy persistencePolicy;
    private final java.time.Clock clock;

    public DefaultSupervisorEngine(SupervisorExecutionValidator validator,
                                    SupervisorPromptBuilder promptBuilder,
                                    SupervisorGraphFactory graphFactory,
                                    SupervisorThreadConversationStateMapper stateMapper,
                                    SupervisorThreadPersistencePolicy persistencePolicy,
                                    java.time.Clock clock) {
        this.validator = validator;
        this.promptBuilder = promptBuilder;
        this.compiledGraph = graphFactory.buildGraph();
        this.stateMapper = stateMapper;
        this.persistencePolicy = persistencePolicy;
        this.clock = clock;
    }

    @Override
    public AgentResult execute(SupervisorDefinition definition, AgentTask rootTask, RunContext context) {
        return executeThread(definition, rootTask, context, Optional.empty()).result();
    }

    @Override
    public ThreadExecutionOutcome executeThread(
            SupervisorDefinition definition,
            AgentTask task,
            RunContext context,
            Optional<ThreadConversationState> previousState
    ) {
        // 1. 校验
        validator.validate(definition, task, context);

        // 2. 构造初始 State
        SupervisorAgentState initialState;
        if (previousState.isEmpty()) {
            // 新线程
            initialState = stateMapper.createInitialState(definition, task, context);
        } else {
            // 续接线程
            initialState = stateMapper.createContinuedState(definition, task, context, previousState.get());
        }

        // 3. 执行图
        SupervisorAgentState finalState;
        try {
            finalState = compiledGraph.invoke(initialState.data())
                    .orElseThrow(() -> new AgentFrameworkException(
                            AgentErrorCode.INTERNAL_ERROR,
                            "Supervisor graph execution returned empty state"));
        } catch (AgentFrameworkException e) {
            log.error("Supervisor execution failed: runId={}, supervisor={}, errorCode={}",
                    context.runId(), definition.name(), e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.error("Supervisor execution unexpected error: runId={}, supervisor={}",
                    context.runId(), definition.name(), e);
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor execution failed",
                    e
            );
        }

        // 4. 读取最终结果
        AgentResult finalResult = getFinalResult(finalState);
        if (finalResult == null) {
            log.error("Supervisor execution completed without finalResult: runId={}, supervisor={}",
                    context.runId(), definition.name());
            throw new AgentFrameworkException(
                    AgentErrorCode.INTERNAL_ERROR,
                    "Supervisor execution completed without result"
            );
        }

        // 5. 调用 SupervisorThreadPersistencePolicy 判断是否稳定
        Optional<ThreadConversationState> conversationState = Optional.empty();
        if (persistencePolicy.isPersistable(finalResult, finalState)) {
            try {
                // 6. 稳定时提取 ThreadConversationState
                conversationState = Optional.of(
                        stateMapper.extractStableState(
                                definition.name(),
                                context.runId(),
                                finalState,
                                Instant.now(clock)
                        )
                );
            } catch (AgentFrameworkException e) {
                // 状态映射失败，conversationState 为空
                log.warn("Supervisor stable state extraction failed: runId={}, errorCode={}",
                        context.runId(), e.getErrorCode());
                conversationState = Optional.empty();
            }
        }

        // 7. 不稳定时 conversationState 为空
        return new ThreadExecutionOutcome(finalResult, conversationState);
    }

    private AgentResult getFinalResult(SupervisorAgentState state) {
        return state.<AgentResult>value(SupervisorStateKeys.FINAL_RESULT).orElse(null);
    }
}
